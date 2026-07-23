import { CircleCheck } from 'lucide-react'
import { rightSideSignalPresentation } from '../../lib/shortTermRightSide'
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
      <span className="whitespace-nowrap text-[11px] font-medium text-ink-400">综合分</span>
      <ScoreBadge value={value} />
    </div>
  )
}
