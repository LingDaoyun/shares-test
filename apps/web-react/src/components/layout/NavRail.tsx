import { useLocation, useNavigate } from 'react-router-dom'
import { Activity, BarChart3, ClipboardList, Globe2, LayoutDashboard, ListChecks, Newspaper, Search, Settings2, Star, type LucideIcon } from 'lucide-react'
import { useAppStore } from '../../store/appStore'
import { navItems, pathToPage, type PageKey } from './pageMeta'

const pageIcons: Record<PageKey, LucideIcon> = {
  limitUp: LayoutDashboard,
  shortTerm: Activity,
  potentialLeader: Search,
  market: BarChart3,
  overseas: Globe2,
  policy: Newspaper,
  watchlist: Star,
  tradeReview: ClipboardList,
  rules: ListChecks,
  settings: Settings2
}

export function NavRail() {
  const location = useLocation()
  const navigate = useNavigate()
  const rules = useAppStore((s) => s.rules)
  const runtimeConfig = useAppStore((s) => s.runtimeConfig)

  const key = location.pathname.replace(/^\/+|\/+$/g, '').trim()
  const current = pathToPage[key] ?? 'limitUp'

  const pagePath: Record<PageKey, string> = {
    limitUp: 'limit-up',
    market: 'market',
    shortTerm: 'short-term',
    potentialLeader: 'potential-leader',
    overseas: 'overseas',
    policy: 'policy',
    watchlist: 'watchlist',
    tradeReview: 'trade-review',
    rules: 'rules',
    settings: 'settings'
  }

  const counts: Record<PageKey, string> = {
    limitUp: '首页',
    market: '长投',
    shortTerm: '盘中',
    potentialLeader: '5交易日',
    overseas: '美日韩',
    policy: '产业',
    watchlist: '自选',
    tradeReview: '复盘',
    rules: `${rules.length} 条`,
    settings: runtimeConfig ? '已加载' : '未读取'
  }

  return (
    <nav aria-label="模块菜单" className="sticky top-4 flex max-h-[calc(100dvh-2rem)] w-14 shrink-0 flex-col gap-1.5 self-start overflow-y-auto rounded-2xl border border-line bg-white p-1.5 lg:w-52 lg:p-3">
      <p className="hidden px-2 pb-2 pt-1 text-xs font-medium tracking-wide text-ink-400 lg:block">功能导航</p>
      {navItems.map((item) => {
        const active = current === item.key
        const Icon = pageIcons[item.key]
        return (
          <button
            key={item.key}
            type="button"
            aria-label={item.label}
            title={item.label}
            aria-current={active ? 'page' : undefined}
            onClick={() => navigate(`/${pagePath[item.key]}`)}
            className={`nav-pill ${active ? 'nav-pill-active' : ''}`}
          >
            <Icon aria-hidden="true" className={`h-4 w-4 shrink-0 ${active ? 'text-brand-600' : 'text-ink-400'}`} />
            <span className={`sr-only text-sm font-semibold lg:not-sr-only ${active ? 'text-brand-600' : 'text-ink-900'}`}>
              {item.label}
            </span>
            <small className={`sr-only tabular text-[10px] lg:not-sr-only lg:ml-auto ${active ? 'text-brand-500' : 'text-ink-400'}`}>
              {counts[item.key]}
            </small>
          </button>
        )
      })}
    </nav>
  )
}
