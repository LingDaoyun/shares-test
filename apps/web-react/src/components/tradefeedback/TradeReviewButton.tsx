import { useEffect, useState } from 'react'
import { ClipboardCheck, ClipboardPlus, Loader2 } from 'lucide-react'
import { toast } from '../ui/Toast'
import { extractErrorMessage } from '../../lib/format'
import { useTradeFeedbackStore } from '../../store/tradeFeedbackStore'

export interface TradeReviewButtonProps {
  symbol: string
  companyName: string
  sourceModule: string
  action: string
  score: number | null
  ruleVersion: string
  recommendedPrice: number | null
  recommendedAt: string | null
  payload: unknown
}

function unavailableReason(recommendedPrice: number | null, recommendedAt: string | null) {
  if (recommendedPrice == null && !recommendedAt) return '缺少实时每股价格和推荐时间'
  if (recommendedPrice == null) return '缺少实时每股价格'
  if (!recommendedAt) return '缺少推荐时间'
  return null
}

export function TradeReviewButton({
  symbol,
  companyName,
  sourceModule,
  action,
  score,
  ruleVersion,
  recommendedPrice,
  recommendedAt,
  payload
}: TradeReviewButtonProps) {
  const loadCases = useTradeFeedbackStore((state) => state.loadCases)
  const getCaseId = useTradeFeedbackStore((state) => state.getCaseId)
  const ensureCase = useTradeFeedbackStore((state) => state.ensureCase)
  const [saving, setSaving] = useState(false)
  const reason = unavailableReason(recommendedPrice, recommendedAt)
  const caseId = recommendedAt
    ? getCaseId({ symbol, sourceModule, ruleVersion, recommendedAt })
    : undefined
  const reviewing = Boolean(caseId)

  useEffect(() => {
    void loadCases().catch(() => undefined)
  }, [loadCases])

  const createCase = async () => {
    if (reason || reviewing || saving || recommendedPrice == null || !recommendedAt) return
    setSaving(true)
    try {
      await ensureCase({
        symbol,
        companyName,
        sourceModule,
        recommendationAction: action,
        recommendationScore: score,
        ruleVersion,
        recommendedPrice,
        recommendedAt,
        recommendationPayload: payload
      })
      toast.success(`${symbol} 已加入复盘`)
    } catch (error) {
      toast.error(`加入复盘失败：${extractErrorMessage(error)}`)
    } finally {
      setSaving(false)
    }
  }

  const title = reason ?? (reviewing ? '已加入复盘' : '加入复盘')

  return (
    <button
      type="button"
      title={title}
      aria-label={`${title} ${symbol}`}
      aria-pressed={reviewing}
      disabled={Boolean(reason) || saving}
      onClick={(event) => {
        event.stopPropagation()
        void createCase()
      }}
      className={`inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border transition focus:outline-none focus:ring-2 focus:ring-brand-200 disabled:cursor-not-allowed ${
        reviewing
          ? 'border-emerald-300 bg-emerald-50 text-emerald-600'
          : reason
            ? 'border-line bg-line-soft/30 text-ink-300'
            : 'border-line bg-white text-ink-400 hover:border-brand-300 hover:text-brand-600'
      }`}
    >
      {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : reviewing ? <ClipboardCheck className="h-4 w-4" /> : <ClipboardPlus className="h-4 w-4" />}
    </button>
  )
}
