import type { ReactNode } from 'react'
import { Empty } from './Empty'

export interface Column<T> {
  key: string
  title: ReactNode
  /** 单元格渲染；不传则取 row[key] */
  render?: (row: T, index: number) => ReactNode
  className?: string
  width?: string
  align?: 'left' | 'right' | 'center'
}

interface DataTableProps<T> {
  columns: Column<T>[]
  data: T[]
  rowKey: (row: T, index: number) => string
  /** 选中行判定，命中时加高亮 */
  isSelected?: (row: T) => boolean
  onRowClick?: (row: T) => void
  emptyText?: string
}

const alignClass = { left: 'text-left', right: 'text-right', center: 'text-center' } as const

export function DataTable<T>({
  columns,
  data,
  rowKey,
  isSelected,
  onRowClick,
  emptyText
}: DataTableProps<T>) {
  if (data.length === 0) {
    return <Empty text={emptyText} />
  }
  return (
    <div className="overflow-x-auto">
      <table className="data-table">
        <thead>
          <tr>
            {columns.map((col) => (
              <th key={col.key} className={alignClass[col.align ?? 'left']} style={{ width: col.width }}>
                {col.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((row, index) => {
            const selected = isSelected?.(row) ?? false
            return (
              <tr
                key={rowKey(row, index)}
                className={selected ? 'row-selected' : ''}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                style={onRowClick ? { cursor: 'pointer' } : undefined}
              >
                {columns.map((col) => (
                  <td key={col.key} className={`${alignClass[col.align ?? 'left']} ${col.className ?? ''}`}>
                    {col.render ? col.render(row, index) : String((row as Record<string, unknown>)[col.key] ?? '')}
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
