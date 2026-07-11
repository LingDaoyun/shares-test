import type { ReactNode } from 'react'

type Tone = 'brand' | 'success' | 'warning' | 'danger' | 'sky' | 'neutral'

const toneClass: Record<Tone, string> = {
  brand: 'tag-brand',
  success: 'tag-success',
  warning: 'tag-warning',
  danger: 'tag-danger',
  sky: 'tag-sky',
  neutral: 'tag'
}

interface TagProps {
  children: ReactNode
  tone?: Tone
  className?: string
}

export function Tag({ children, tone = 'neutral', className = '' }: TagProps) {
  return <span className={`${toneClass[tone]} ${className}`}>{children}</span>
}

interface ScoreBadgeProps {
  value: number | null | undefined
  /** 100 分制阈值，用于配色 */
  max?: number
}

// 评分胶囊：按分值区间着色
export function ScoreBadge({ value, max = 100 }: ScoreBadgeProps) {
  if (value === null || value === undefined) {
    return <span className="tag text-ink-400">待补充</span>
  }
  const ratio = max > 0 ? value / max : 0
  const tone: Tone = ratio >= 0.75 ? 'success' : ratio >= 0.5 ? 'brand' : ratio >= 0.3 ? 'warning' : 'neutral'
  return (
    <span className={`${toneClass[tone]} tabular font-semibold`}>
      {Number(value).toFixed(1)}
    </span>
  )
}
