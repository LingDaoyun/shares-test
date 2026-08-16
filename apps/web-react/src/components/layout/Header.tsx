import { forwardRef } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { RefreshCw, Settings, ArrowLeft } from 'lucide-react'
import { useAppStore } from '../../store/appStore'
import { pageMeta, pathToPage, type PageKey } from './pageMeta'
import { Spinner } from '../ui/Loader'

export const Header = forwardRef<HTMLElement>(function Header(_props, ref) {
  const location = useLocation()
  const navigate = useNavigate()
  const loading = useAppStore((s) => s.loading)
  const loadAll = useAppStore((s) => s.loadAll)
  const llmConfig = useAppStore((s) => s.llmConfig)

  const key = (location.pathname.replace(/^\//, '').trim() as PageKey) || 'shortTerm'
  const page = pathToPage[key] ?? 'shortTerm'
  const meta = pageMeta[page]

  const llmLabel = llmConfig ? `${llmConfig.provider} / ${llmConfig.model}` : '模型未读取'
  const isSettings = page === 'settings'

  return (
    <header ref={ref} className="card flex flex-wrap items-end justify-between gap-4 px-6 py-5">
      <div className="min-w-0">
        <div className="eyebrow mb-1">{meta.eyebrow}</div>
        <h1 className="text-2xl font-bold tracking-tight text-ink-900">{meta.title}</h1>
        <p className="mt-1 max-w-2xl text-sm text-ink-600">{meta.description}</p>
      </div>

      <div className="flex flex-col items-end gap-3">
        <div className="flex flex-wrap items-center justify-end gap-2">
          <StatusChip label="模型" value={llmLabel} />
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => navigate(isSettings ? '/short-term' : '/settings')}
            className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-white px-3 py-2 text-sm font-medium text-ink-600 transition hover:border-brand-300 hover:text-brand-600"
          >
            {isSettings ? <ArrowLeft className="h-4 w-4" /> : <Settings className="h-4 w-4" />}
            {isSettings ? '返回短线' : '系统配置'}
          </button>
          <button
            type="button"
            title="刷新全部数据"
            onClick={() => void loadAll()}
            disabled={loading}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-line bg-white text-ink-600 transition hover:border-brand-300 hover:text-brand-600 disabled:opacity-50"
          >
            {loading ? <Spinner className="text-brand-500" /> : <RefreshCw className="h-4 w-4" />}
          </button>
        </div>
      </div>
    </header>
  )
})

function StatusChip({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center gap-1.5 rounded-lg border border-line bg-white px-2.5 py-1.5">
      <span className="text-xs text-ink-400">{label}</span>
      <span className="text-xs font-semibold text-ink-900">{value}</span>
    </div>
  )
}
