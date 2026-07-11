import type { ReactNode } from 'react'

interface SectionBannerProps {
  eyebrow?: string
  title: ReactNode
  description?: ReactNode
  extra?: ReactNode
}

// 页内段落横幅：小标题 + 描述 + 右侧操作
export function SectionBanner({ eyebrow, title, description, extra }: SectionBannerProps) {
  return (
    <div className="card flex flex-wrap items-end justify-between gap-3 px-5 py-4">
      <div className="min-w-0">
        {eyebrow && <div className="eyebrow mb-1">{eyebrow}</div>}
        <h2 className="text-lg font-semibold text-ink-900">{title}</h2>
        {description && <p className="mt-1 text-sm text-ink-600">{description}</p>}
      </div>
      {extra && <div className="shrink-0">{extra}</div>}
    </div>
  )
}
