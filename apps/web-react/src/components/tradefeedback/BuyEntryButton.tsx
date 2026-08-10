import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { Loader2, ShoppingCart, X } from 'lucide-react'
import { addTradeFill } from '../../api/client'
import { acquireBodyScrollLock } from '../ui/DetailOverlay'
import { Button } from '../ui/Button'
import { toast } from '../ui/Toast'
import {
  extractTradeMutationError,
  formatShanghaiDateTimeLocal,
  parseShanghaiDateTimeLocal
} from '../../lib/tradeReview'
import { useTradeFeedbackStore } from '../../store/tradeFeedbackStore'
import type { UpsertTradeFillRequest } from '../../types'

export interface BuyEntryButtonProps {
  symbol: string
  companyName: string
  latestPrice: number | null | undefined
  recommendedAt: string | null
  attestationToken: string | null
}

const FOCUSABLE_SELECTOR = [
  'button:not([disabled])',
  'input:not([disabled])',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

function unavailableReason(attestationToken: string | null, recommendedAt: string | null) {
  if (!attestationToken) return '当前推荐缺少可验证的实时价格或时间'
  if (!recommendedAt) return '缺少推荐时间'
  return null
}

function initialPrice(latestPrice: number | null | undefined) {
  return latestPrice === null || latestPrice === undefined ? '' : String(latestPrice)
}

export function BuyEntryButton({
  symbol,
  companyName,
  latestPrice,
  recommendedAt,
  attestationToken
}: BuyEntryButtonProps) {
  const ensureCase = useTradeFeedbackStore((state) => state.ensureCase)
  const upsertCase = useTradeFeedbackStore((state) => state.upsertCase)
  const [open, setOpen] = useState(false)
  const [price, setPrice] = useState('')
  const [quantity, setQuantity] = useState('100')
  const [executedAt, setExecutedAt] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)
  const returnFocusRef = useRef<HTMLElement | null>(null)
  const reason = unavailableReason(attestationToken, recommendedAt)

  const openDialog = (trigger: HTMLElement) => {
    if (reason || !attestationToken) return
    returnFocusRef.current = trigger
    setPrice(initialPrice(latestPrice))
    setQuantity('100')
    setExecutedAt(formatShanghaiDateTimeLocal(new Date().toISOString()))
    setError('')
    setOpen(true)
  }

  const closeDialog = () => {
    if (savingRef.current) return
    setOpen(false)
  }

  const title = reason ?? `买入 ${companyName} ${symbol}`

  return (
    <>
      <Button
        type="button"
        variant="primary"
        title={title}
        aria-label={`买入 ${companyName} ${symbol}`}
        disabled={Boolean(reason)}
        icon={<ShoppingCart className="h-4 w-4" />}
        onClick={(event) => {
          event.stopPropagation()
          openDialog(event.currentTarget)
        }}
      >
        买入
      </Button>
      {open ? (
        <BuyEntryDialog
          symbol={symbol}
          companyName={companyName}
          recommendedAt={recommendedAt}
          attestationToken={attestationToken}
          price={price}
          quantity={quantity}
          executedAt={executedAt}
          error={error}
          saving={saving}
          returnFocus={returnFocusRef.current}
          onPriceChange={setPrice}
          onQuantityChange={setQuantity}
          onExecutedAtChange={setExecutedAt}
          onClose={closeDialog}
          onSubmit={async () => {
            if (savingRef.current || !attestationToken) return
            setError('')
            const numericPrice = Number(price)
            const numericQuantity = Number(quantity)
            const timestamp = parseShanghaiDateTimeLocal(executedAt)
            if (!Number.isFinite(numericPrice) || numericPrice <= 0) {
              setError('买入价格必须大于 0')
              return
            }
            if (!Number.isInteger(numericQuantity) || numericQuantity <= 0) {
              setError('买入股数必须为正整数')
              return
            }
            if (!timestamp) {
              setError('请输入有效的买入时间')
              return
            }
            const request: UpsertTradeFillRequest = {
              side: 'BUY',
              executedAt: timestamp,
              price: numericPrice,
              quantity: numericQuantity
            }
            savingRef.current = true
            setSaving(true)
            try {
              const tradeCase = await ensureCase({ attestationToken })
              const updated = await addTradeFill(tradeCase.caseId, request)
              upsertCase(updated)
              setOpen(false)
              toast.success(`${symbol} 买入已记录，可在交易复盘查看`)
            } catch (submitError) {
              const message = extractTradeMutationError(submitError)
              setError(message)
              toast.error(`买入记录失败：${message}`)
            } finally {
              savingRef.current = false
              setSaving(false)
            }
          }}
        />
      ) : null}
    </>
  )
}

function BuyEntryDialog({
  symbol,
  companyName,
  recommendedAt,
  attestationToken,
  price,
  quantity,
  executedAt,
  error,
  saving,
  returnFocus,
  onPriceChange,
  onQuantityChange,
  onExecutedAtChange,
  onClose,
  onSubmit
}: {
  symbol: string
  companyName: string
  recommendedAt: string | null
  attestationToken: string | null
  price: string
  quantity: string
  executedAt: string
  error: string
  saving: boolean
  returnFocus: HTMLElement | null
  onPriceChange: (value: string) => void
  onQuantityChange: (value: string) => void
  onExecutedAtChange: (value: string) => void
  onClose: () => void
  onSubmit: () => Promise<void>
}) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const firstFieldRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const releaseScrollLock = acquireBodyScrollLock(document.body)
    const animationFrame = window.requestAnimationFrame(() => firstFieldRef.current?.focus())
    return () => {
      window.cancelAnimationFrame(animationFrame)
      releaseScrollLock()
      if (returnFocus?.isConnected) returnFocus.focus()
    }
  }, [returnFocus])

  const handleDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation()
      if (!saving) {
        event.preventDefault()
        onClose()
      }
      return
    }
    if (event.key !== 'Tab') return
    const focusable = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) ?? [])
    if (!focusable.length) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    await onSubmit()
  }

  const frame = (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-ink-900/40 p-0 sm:items-center sm:p-4" onMouseDown={(event) => {
      event.stopPropagation()
      if (event.target === event.currentTarget && !saving) onClose()
    }}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="buy-entry-dialog-title"
        className="max-h-[92dvh] w-full overflow-y-auto rounded-t-xl border border-line bg-white shadow-float sm:max-w-lg sm:rounded-xl"
        onKeyDown={handleDialogKeyDown}
      >
        <div className="sticky top-0 z-10 flex items-center justify-between border-b border-line bg-white px-4 py-3">
          <div>
            <h2 id="buy-entry-dialog-title" className="text-base font-semibold text-ink-900">确认买入</h2>
            <p className="mt-0.5 text-xs text-ink-500">{symbol} {companyName}</p>
          </div>
          <button
            type="button"
            aria-label="关闭买入确认"
            disabled={saving}
            onClick={onClose}
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-line-soft text-ink-500 transition hover:bg-line-soft disabled:cursor-not-allowed disabled:text-ink-300"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <form noValidate onSubmit={(event) => void submit(event)} className="space-y-4 px-4 py-5">
          <div className="rounded-lg border border-line-soft bg-line-soft/30 px-3 py-2 text-xs leading-relaxed text-ink-500">
            <div className="flex items-center justify-between gap-2">
              <span>推荐时间</span>
              <span className="tabular text-ink-700">{recommendedAt ?? '待补'}</span>
            </div>
            <div className="mt-1 flex items-center justify-between gap-2">
              <span>凭证状态</span>
              <span className="text-ink-700">{attestationToken ? '服务端已验证' : '缺失'}</span>
            </div>
          </div>
          <label className="block">
            <span className="field-label">买入时间</span>
            <input
              className="field"
              ref={firstFieldRef}
              aria-label="买入时间"
              type="datetime-local"
              step="1"
              required
              value={executedAt}
              onChange={(event) => onExecutedAtChange(event.target.value)}
            />
          </label>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <label className="block">
              <span className="field-label">买入价格</span>
              <input
                className="field tabular"
                aria-label="买入价格"
                type="number"
                min="0.01"
                step="0.01"
                inputMode="decimal"
                required
                value={price}
                onChange={(event) => onPriceChange(event.target.value)}
              />
            </label>
            <label className="block">
              <span className="field-label">买入股数</span>
              <input
                className="field tabular"
                aria-label="买入股数"
                type="number"
                min="1"
                step="1"
                inputMode="numeric"
                required
                value={quantity}
                onChange={(event) => onQuantityChange(event.target.value)}
              />
            </label>
          </div>
          {error ? <div role="alert" className="border-l-2 border-red-500 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
          <div className="flex flex-col-reverse gap-2 border-t border-line-soft pt-4 sm:flex-row sm:justify-end">
            <Button type="button" variant="ghost" disabled={saving} onClick={onClose}>取消</Button>
            <Button type="submit" variant="primary" loading={saving} disabled={saving}>
              {saving ? '保存中' : '确认买入'}
            </Button>
          </div>
        </form>
        {saving ? (
          <span className="sr-only" aria-live="polite">
            <Loader2 className="h-4 w-4 animate-spin" /> 正在保存买入记录
          </span>
        ) : null}
      </div>
    </div>
  )

  return createPortal(frame, document.body)
}
