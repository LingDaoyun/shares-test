import { CircleCheck } from 'lucide-react'
import { rightSideSignalPresentation } from '../../lib/shortTermRightSide'
import type { ShortTermMomentumQuality } from '../../types'
import { ScoreBadge, Tag } from '../ui/Badge'

export function RightSideSignalTag({ signal }: { signal: string | null | undefined }) {
  const presentation = rightSideSignalPresentation(signal)

  return (
    <Tag className={presentation.className}>
      {presentation.emphasized ? <CircleCheck aria-hidden="true" size={13} strokeWidth={2.2} /> : null}
      {presentation.label}
    </Tag>
  )
}

export function CompositeScoreBadge({ value }: { value: number | null | undefined }) {
  return (
    <div className="flex items-center gap-1.5 md:flex-col md:items-end md:gap-1">
      <span className="whitespace-nowrap text-[11px] font-medium text-ink-400">排序分</span>
      <ScoreBadge value={value} />
    </div>
  )
}

export function MomentumQualityTags({
  quality
}: {
  quality: ShortTermMomentumQuality | null | undefined
}) {
  if (!quality) return null
  const turnoverTone = quality.turnoverBand === 'PREFERRED'
    ? 'success'
    : quality.turnoverBand === 'OBSERVATION'
      ? 'warning'
      : 'danger'
  const closeTone = quality.extremeUpperShadow
    ? 'danger'
    : quality.closeStrengthScore >= 75
      ? 'success'
      : 'warning'

  return (
    <>
      <Tag tone={turnoverTone}>
        换手 {compactNumber(quality.turnoverRatePercent)}%
      </Tag>
      <Tag tone={closeTone}>
        {quality.closeStrengthLabel}
        {quality.provisional ? ' · 暂定' : ''}
      </Tag>
    </>
  )
}

function compactNumber(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) return '待补'
  return Number(value.toFixed(2)).toString()
}
