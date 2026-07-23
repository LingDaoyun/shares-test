import { X } from 'lucide-react'
import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode, type RefObject } from 'react'
import { createPortal } from 'react-dom'

interface DetailOverlayProps {
  open: boolean
  title: ReactNode
  subtitle?: ReactNode
  children: ReactNode
  onClose: () => void
  initialFocusRef?: RefObject<HTMLElement>
}

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

const bodyScrollLocks = new WeakMap<HTMLElement, { count: number; originalOverflow: string }>()

export function isBackdropClose(target: EventTarget | null, currentTarget: EventTarget | null) {
  return target === currentTarget
}

export function isEscapeClose(key: string, defaultPrevented = false) {
  return key === 'Escape' && !defaultPrevented
}

export function acquireBodyScrollLock(body: HTMLElement) {
  const state = bodyScrollLocks.get(body) ?? { count: 0, originalOverflow: body.style.overflow }
  state.count += 1
  bodyScrollLocks.set(body, state)
  body.style.overflow = 'hidden'
  let released = false

  return () => {
    if (released) return
    released = true
    const current = bodyScrollLocks.get(body)
    if (!current) return
    current.count -= 1
    if (current.count > 0) return
    body.style.overflow = current.originalOverflow
    bodyScrollLocks.delete(body)
  }
}

export function resolveReturnFocus(returnFocus: HTMLElement | null, fallback: HTMLElement | null) {
  if (returnFocus?.isConnected) return returnFocus
  if (fallback?.isConnected) return fallback
  return null
}

export function resolveDetailSelection<T>(items: T[], selectedId: string | null, getId: (item: T) => string) {
  if (!selectedId) return null
  return items.find((item) => getId(item) === selectedId) ?? null
}

export function DetailOverlay({
  open,
  title,
  subtitle,
  children,
  onClose,
  initialFocusRef
}: DetailOverlayProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const titleId = useId()
  const subtitleId = useId()

  useEffect(() => {
    if (!open || typeof document === 'undefined') return

    const returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const releaseScrollLock = acquireBodyScrollLock(document.body)

    const animationFrame = window.requestAnimationFrame(() => {
      const initialFocus = initialFocusRef?.current ?? dialogRef.current
      initialFocus?.focus()
    })

    return () => {
      window.cancelAnimationFrame(animationFrame)
      releaseScrollLock()
      resolveReturnFocus(returnFocus, document.querySelector<HTMLElement>('main'))?.focus()
    }
  }, [initialFocusRef, open])

  if (!open) return null

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (isEscapeClose(event.key, event.defaultPrevented)) {
      event.preventDefault()
      event.stopPropagation()
      onClose()
      return
    }
    if (event.key !== 'Tab') return

    const focusable = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) ?? [])
      .filter((element) => element.offsetParent !== null)
    if (!focusable.length) {
      event.preventDefault()
      dialogRef.current?.focus()
      return
    }

    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const frame = (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink-900/35 p-3 backdrop-blur-[1px] sm:p-5"
      onMouseDown={(event) => {
        if (isBackdropClose(event.target, event.currentTarget)) onClose()
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={subtitle ? subtitleId : undefined}
        tabIndex={-1}
        className="flex max-h-[92dvh] w-full max-w-[1180px] flex-col overflow-hidden rounded-xl border border-line bg-white shadow-float outline-none sm:max-h-[88dvh]"
        onKeyDown={handleKeyDown}
      >
        <header className="flex shrink-0 items-start justify-between gap-4 border-b border-line-soft bg-white px-4 py-3 sm:px-5 sm:py-4">
          <div className="min-w-0">
            <h2 id={titleId} className="truncate text-base font-semibold text-ink-900 sm:text-lg">{title}</h2>
            {subtitle ? <p id={subtitleId} className="mt-0.5 truncate text-xs text-ink-500 sm:text-sm">{subtitle}</p> : null}
          </div>
          <button
            type="button"
            aria-label="关闭详情"
            title="关闭详情"
            className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-line-soft text-ink-500 outline-none transition hover:border-brand-200 hover:bg-brand-50 hover:text-brand-700 focus-visible:ring-2 focus-visible:ring-brand-300"
            onClick={onClose}
          >
            <X className="h-4 w-4" />
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-3 sm:p-5">
          {children}
        </div>
      </div>
    </div>
  )

  return typeof document === 'undefined' ? frame : createPortal(frame, document.body)
}
