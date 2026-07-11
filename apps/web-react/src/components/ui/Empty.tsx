import type { ReactNode } from 'react'
import { Inbox } from 'lucide-react'

interface EmptyProps {
  text?: ReactNode
}

export function Empty({ text = '暂无数据' }: EmptyProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-12 text-ink-400">
      <Inbox className="h-8 w-8" strokeWidth={1.5} />
      <span className="text-sm">{text}</span>
    </div>
  )
}
