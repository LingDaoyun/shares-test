import { Loader2 } from 'lucide-react'

interface LoaderProps {
  text?: string
  className?: string
}

export function Loader({ text = '加载中', className = '' }: LoaderProps) {
  return (
    <div className={`flex items-center justify-center gap-2 py-10 text-ink-400 ${className}`}>
      <Loader2 className="h-4 w-4 animate-spin text-brand-500" />
      <span className="text-sm">{text}…</span>
    </div>
  )
}

export function Spinner({ className = '' }: { className?: string }) {
  return <Loader2 className={`h-4 w-4 animate-spin ${className}`} />
}
