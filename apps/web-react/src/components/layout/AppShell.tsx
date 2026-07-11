import { Outlet } from 'react-router-dom'
import { Header } from './Header'
import { NavRail } from './NavRail'
import { useBootstrap } from '../../hooks/useBootstrap'

export function AppShell() {
  useBootstrap()
  return (
    <div className="min-h-full">
      <div className="mx-auto flex max-w-[1480px] flex-col gap-5 px-5 py-6">
        <Header />
        <NavRail />
        <main className="animate-fade-in">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
