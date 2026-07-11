import { Component, Suspense, lazy, type ComponentType, type ErrorInfo, type ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/layout/AppShell'
import { Loader } from './components/ui/Loader'
import { ToastViewport } from './components/ui/Toast'

const LAZY_RELOAD_KEY = 'ai-stock-route-chunk-reloaded'

function lazyPage<T extends ComponentType>(loader: () => Promise<T>) {
  return lazy(async () => {
    try {
      const Component = await loader()
      if (typeof window !== 'undefined') {
        window.sessionStorage.removeItem(LAZY_RELOAD_KEY)
      }
      return { default: Component }
    } catch (error) {
      if (shouldReloadForChunkError(error)) {
        window.sessionStorage.setItem(LAZY_RELOAD_KEY, '1')
        window.location.reload()
        return new Promise<{ default: T }>(() => {})
      }
      throw error
    }
  })
}

function shouldReloadForChunkError(error: unknown) {
  if (typeof window === 'undefined') return false
  if (window.sessionStorage.getItem(LAZY_RELOAD_KEY) === '1') return false
  const message = error instanceof Error ? error.message : String(error)
  return /Failed to fetch dynamically imported module|Importing a module script failed|Loading chunk|dynamically imported module/i.test(message)
}

class AppErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('页面渲染失败', error, info)
  }

  private reload = () => {
    window.sessionStorage.removeItem(LAZY_RELOAD_KEY)
    window.location.reload()
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div className="mx-auto flex min-h-screen max-w-[720px] flex-col items-start justify-center gap-4 px-5">
        <div className="eyebrow">PAGE RECOVERY</div>
        <h1 className="text-2xl font-semibold text-ink-900">页面资源已更新</h1>
        <p className="text-sm leading-relaxed text-ink-500">
          本地服务刚刚重新打包，浏览器里可能还保留着旧版本页面。刷新后会加载最新资源。
        </p>
        <button
          type="button"
          className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-brand-700"
          onClick={this.reload}
        >
          刷新页面
        </button>
      </div>
    )
  }
}

const MarketScanPage = lazyPage(() => import('./pages/MarketScanPage').then((m) => m.MarketScanPage))
const ShortTermPage = lazyPage(() => import('./pages/ShortTermPage').then((m) => m.ShortTermPage))
const TechTrackerPage = lazyPage(() => import('./pages/TechTrackerPage').then((m) => m.TechTrackerPage))
const MispricingPage = lazyPage(() => import('./pages/MispricingPage').then((m) => m.MispricingPage))
const CycleTrialPage = lazyPage(() => import('./pages/CycleTrialPage').then((m) => m.CycleTrialPage))
const DailySignalsPage = lazyPage(() => import('./pages/DailySignalsPage').then((m) => m.DailySignalsPage))
const WatchlistPage = lazyPage(() => import('./pages/WatchlistPage').then((m) => m.WatchlistPage))
const RulesPage = lazyPage(() => import('./pages/RulesPage').then((m) => m.RulesPage))
const SettingsPage = lazyPage(() => import('./pages/SettingsPage').then((m) => m.SettingsPage))

export default function App() {
  return (
    <>
      <AppErrorBoundary>
        <Suspense fallback={<div className="mx-auto max-w-[1480px] px-5 py-10"><Loader text="页面加载中" /></div>}>
          <Routes>
            <Route element={<AppShell />}>
              <Route index element={<Navigate to="/short-term" replace />} />
              <Route path="market" element={<MarketScanPage />} />
              <Route path="short-term" element={<ShortTermPage />} />
              <Route path="backtest" element={<Navigate to="/short-term" replace />} />
              <Route path="tech" element={<TechTrackerPage />} />
              <Route path="mispricing" element={<MispricingPage />} />
              <Route path="cycle" element={<CycleTrialPage />} />
              <Route path="signals" element={<DailySignalsPage />} />
              <Route path="watchlist" element={<WatchlistPage />} />
              <Route path="rules" element={<RulesPage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="*" element={<Navigate to="/short-term" replace />} />
            </Route>
          </Routes>
        </Suspense>
      </AppErrorBoundary>
      <ToastViewport />
    </>
  )
}
