import { useLocation, useNavigate } from 'react-router-dom'
import { useAppStore } from '../../store/appStore'
import { navItems, pathToPage, type PageKey } from './pageMeta'

export function NavRail() {
  const location = useLocation()
  const navigate = useNavigate()
  const rules = useAppStore((s) => s.rules)
  const runtimeConfig = useAppStore((s) => s.runtimeConfig)

  const key = (location.pathname.replace(/^\//, '').trim() as PageKey) || 'shortTerm'
  const current = pathToPage[key] ?? 'shortTerm'

  const pagePath: Record<PageKey, string> = {
    market: 'market',
    shortTerm: 'short-term',
    tech: 'tech',
    mispricing: 'mispricing',
    cycle: 'cycle',
    signals: 'signals',
    watchlist: 'watchlist',
    tradeReview: 'trade-review',
    rules: 'rules',
    settings: 'settings'
  }

  const counts: Record<PageKey, string> = {
    market: '全A',
    shortTerm: '盘中',
    tech: '热门',
    mispricing: '低估',
    cycle: '周期',
    signals: '今日',
    watchlist: '自选',
    tradeReview: '复盘',
    rules: `${rules.length} 条`,
    settings: runtimeConfig ? '已加载' : '未读取'
  }

  return (
    <nav aria-label="模块菜单" className="grid grid-cols-2 gap-2.5 sm:grid-cols-3 lg:grid-cols-5 xl:grid-cols-10">
      {navItems.map((item) => {
        const active = current === item.key
        return (
          <button
            key={item.key}
            type="button"
            aria-current={active ? 'page' : undefined}
            onClick={() => navigate(`/${pagePath[item.key]}`)}
            className={`nav-pill ${active ? 'nav-pill-active' : ''}`}
          >
            <span className={`text-sm font-semibold ${active ? 'text-brand-600' : 'text-ink-900'}`}>
              {item.label}
            </span>
            <small className={`tabular text-xs ${active ? 'text-brand-500' : 'text-ink-400'}`}>
              {counts[item.key]}
            </small>
          </button>
        )
      })}
    </nav>
  )
}
