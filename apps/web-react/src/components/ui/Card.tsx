import type { ReactNode } from 'react'

interface CardProps {
  children: ReactNode
  className?: string
  /** 卡片头部（标题区），可选 */
  title?: ReactNode
  /** 头部右侧操作区 */
  extra?: ReactNode
  /** 是否取消默认内边距（用于内嵌表格的场景） */
  flush?: boolean
}

export function Card({ children, className = '', title, extra, flush }: CardProps) {
  return (
    <section className={`card ${className}`}>
      {(title || extra) && (
        <header className="flex items-center justify-between gap-3 border-b border-line-soft px-5 py-3.5">
          <div className="section-title">{title}</div>
          {extra && <div className="shrink-0">{extra}</div>}
        </header>
      )}
      <div className={flush ? '' : 'p-5'}>{children}</div>
    </section>
  )
}
