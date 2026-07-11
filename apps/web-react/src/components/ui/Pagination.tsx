import { ChevronLeft, ChevronRight } from 'lucide-react'
import { totalPages } from '../../lib/scoring'

interface PaginationProps {
  page: number
  pageSize: number
  total: number
  pageSizeOptions?: number[]
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
}

export function Pagination({
  page,
  pageSize,
  total,
  pageSizeOptions = [6, 10, 20],
  onPageChange,
  onPageSizeChange
}: PaginationProps) {
  const pages = totalPages(total, pageSize)
  const start = total === 0 ? 0 : (page - 1) * pageSize + 1
  const end = Math.min(page * pageSize, total)

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 px-1 py-3 text-sm text-ink-600">
      <div className="tabular">
        共 <span className="font-semibold text-ink-900">{total}</span> 条 · 第 {start}–{end} 条
      </div>
      <div className="flex items-center gap-2">
        <select
          className="rounded-md border border-line bg-white px-2 py-1 text-xs text-ink-600 outline-none focus:border-brand-400"
          value={pageSize}
          onChange={(e) => onPageSizeChange(Number(e.target.value))}
        >
          {pageSizeOptions.map((size) => (
            <option key={size} value={size}>
              {size} 条/页
            </option>
          ))}
        </select>
        <div className="flex items-center gap-1">
          <button
            type="button"
            className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-line bg-white text-ink-600 transition hover:border-brand-300 hover:text-brand-600 disabled:cursor-not-allowed disabled:opacity-40"
            onClick={() => onPageChange(page - 1)}
            disabled={page <= 1}
          >
            <ChevronLeft className="h-4 w-4" />
          </button>
          <span className="tabular min-w-[64px] text-center text-xs">
            {page} / {pages}
          </span>
          <button
            type="button"
            className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-line bg-white text-ink-600 transition hover:border-brand-300 hover:text-brand-600 disabled:cursor-not-allowed disabled:opacity-40"
            onClick={() => onPageChange(page + 1)}
            disabled={page >= pages}
          >
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  )
}
