import { useEffect, useRef } from 'react'
import { Outlet } from 'react-router-dom'
import { Header } from './Header'
import { NavRail } from './NavRail'
import { useBootstrap } from '../../hooks/useBootstrap'

// 把顶栏（header 卡片）的垂直中心写进 CSS 变量，供全局通知锚定；
// 窄窗口下顶栏换行变高时通过 ResizeObserver 自适应。
export function AppShell() {
  useBootstrap()
  const headerRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
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
  }, [])

  return (
    <div className="min-h-full">
      <div className="mx-auto flex max-w-[1680px] items-start gap-2 px-2 py-4 sm:gap-5 sm:px-5">
        <NavRail />
        <div className="flex min-w-0 flex-1 flex-col gap-5">
          <Header ref={headerRef} />
          <main tabIndex={-1} className="min-w-0 animate-fade-in outline-none">
            <Outlet />
          </main>
        </div>
      </div>
    </div>
  )
}
