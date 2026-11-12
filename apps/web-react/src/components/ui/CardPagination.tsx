import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from './Button'

export function CardPagination({ page, pageSize, total, onPageChange, ariaLabel, unit = '只' }: {
  page: number
  pageSize: number
  total: number
  onPageChange: (page: number) => void
  ariaLabel: string
  unit?: string
}) {
  const pages = Math.max(1, Math.ceil(total / pageSize))
  return <nav aria-label={ariaLabel} className="grid shrink-0 items-center justify-items-center gap-2 border-t border-line-soft pt-3 lg:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)]">
    <p className="order-2 text-xs text-ink-400 tabular lg:order-none lg:justify-self-start">
      显示 {total ? (page - 1) * pageSize + 1 : 0}–{Math.min(page * pageSize, total)} / {total} {unit}
    </p>
    <div className="flex items-center justify-center gap-1.5 sm:gap-2">
      <Button type="button" aria-label="上一页" className="h-9 shrink-0 whitespace-nowrap !px-2 py-1.5 text-xs sm:!px-3" disabled={page <= 1}
        icon={<ChevronLeft className="h-3.5 w-3.5" />} onClick={() => onPageChange(page - 1)}><span className="hidden sm:inline">上一页</span></Button>
      <label className="inline-flex h-9 shrink-0 items-center gap-1 whitespace-nowrap rounded-lg bg-canvas px-1.5 text-xs text-ink-600 sm:gap-1.5 sm:px-2">
        <span>第</span>
        <select aria-label="跳转页码" className="rounded-md border border-line bg-white px-1.5 py-1 text-ink-900 outline-none focus-visible:ring-2 focus-visible:ring-brand-300" value={page}
          onChange={event => onPageChange(Number(event.target.value))}>
          {Array.from({ length: pages }, (_, index) => <option key={index + 1} value={index + 1}>{index + 1}</option>)}
        </select>
        <span>/ {pages} 页</span>
      </label>
      <Button type="button" aria-label="下一页" className="h-9 shrink-0 whitespace-nowrap !px-2 py-1.5 text-xs sm:!px-3" disabled={page >= pages}
        onClick={() => onPageChange(page + 1)}><span className="hidden sm:inline">下一页</span><ChevronRight className="h-3.5 w-3.5" /></Button>
    </div>
  </nav>
}
