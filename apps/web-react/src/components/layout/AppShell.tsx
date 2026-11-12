import { Suspense, useEffect, useRef } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { Header } from './Header'
import { NavRail } from './NavRail'
import { pathToPage } from './pageMeta'
import { useBootstrap } from '../../hooks/useBootstrap'
import { Loader } from '../ui/Loader'

// 把顶栏（header 卡片）的垂直中心写进 CSS 变量，供全局通知锚定；
// 窄窗口下顶栏换行变高时通过 ResizeObserver 自适应。
export function AppShell() {
  useBootstrap()
  const location = useLocation()
  const key = location.pathname.replace(/^\/+|\/+$/g, '').trim()
  const page = pathToPage[key] ?? 'limitUp'
  const showHeader = page !== 'limitUp' && page !== 'policy' && page !== 'overseas' && page !== 'batchSearch' && page !== 'shortTerm'
  const headerRef = useRef<HTMLElement | null>(null)
  const mainRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (mainRef.current) mainRef.current.scrollTop = 0
  }, [location.pathname])

  useEffect(() => {
    if (!showHeader) return
    const header = headerRef.current
    if (!header) return
    const sync = () => {
      const rect = header.getBoundingClientRect()
      // 通知卡片约 52px 高，锚点取「顶栏中心 - 半高」，让通知中心与顶栏中心对齐
      const toastTop = Math.max(24, rect.top + rect.height / 2 - 26)
      document.documentElement.style.setProperty('--toast-top', `${Math.round(toastTop)}px`)
    }
    sync()
    const observer = new ResizeObserver(sync)
    observer.observe(header)
    return () => {
      observer.disconnect()
      document.documentElement.style.removeProperty('--toast-top')
    }
  }, [showHeader])

  return (
    <div className="h-dvh w-full overflow-hidden">
      <div className="mx-auto flex h-full w-full max-w-[1680px] items-start gap-2 px-2 py-4 sm:gap-5 sm:px-5">
        <NavRail />
        <section aria-label="页面内容" className="flex h-full min-h-0 min-w-0 flex-1 flex-col gap-4 overflow-hidden rounded-2xl border border-line bg-white p-3 sm:p-4">
          {showHeader ? <Header ref={headerRef} /> : null}
          <main ref={mainRef} tabIndex={-1} className="min-h-0 w-full min-w-0 flex-1 overflow-y-auto overscroll-contain [scrollbar-gutter:stable_both-edges] outline-none">
            <Suspense fallback={<div className="flex h-full items-center justify-center"><Loader text="页面加载中" /></div>}>
              <Outlet />
            </Suspense>
          </main>
        </section>
      </div>
    </div>
  )
}
