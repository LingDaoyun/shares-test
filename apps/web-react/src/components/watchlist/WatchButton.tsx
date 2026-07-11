import { useEffect, useState } from 'react'
import { Loader2, Star } from 'lucide-react'
import { toast } from '../ui/Toast'
import { extractErrorMessage } from '../../lib/format'
import { useWatchlistStore } from '../../store/watchlistStore'

export function WatchButton({ symbol }: { symbol: string }) {
  const entries = useWatchlistStore((state) => state.entries)
  const load = useWatchlistStore((state) => state.load)
  const add = useWatchlistStore((state) => state.add)
  const remove = useWatchlistStore((state) => state.remove)
  const [saving, setSaving] = useState(false)
  const watched = entries.some((entry) => entry.symbol === symbol)

  useEffect(() => {
    void load().catch(() => undefined)
  }, [load])

  const toggle = async () => {
    setSaving(true)
    try {
      if (watched) {
        await remove(symbol)
        toast.info(`${symbol} 已取消特别关注`)
      } else {
        await add(symbol)
        toast.success(`${symbol} 已加入特别关注`)
      }
    } catch (error) {
      toast.error(`特别关注更新失败：${extractErrorMessage(error)}`)
    } finally {
      setSaving(false)
    }
  }

  return (
    <button
      type="button"
      title={watched ? '取消特别关注' : '加入特别关注'}
      aria-label={watched ? `取消特别关注 ${symbol}` : `特别关注 ${symbol}`}
      aria-pressed={watched}
      disabled={saving}
      onClick={(event) => {
        event.stopPropagation()
        void toggle()
      }}
      className={`inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border transition focus:outline-none focus:ring-2 focus:ring-brand-200 disabled:cursor-wait ${
        watched
          ? 'border-amber-300 bg-amber-50 text-amber-600 hover:bg-amber-100'
          : 'border-line bg-white text-ink-400 hover:border-amber-300 hover:text-amber-600'
      }`}
    >
      {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Star className={`h-4 w-4 ${watched ? 'fill-current' : ''}`} />}
    </button>
  )
}
