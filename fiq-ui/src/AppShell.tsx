import { useQuery } from '@tanstack/react-query'
import { Activity, Cable, Database, FileSliders, Menu, Moon, PanelLeftClose, PanelLeftOpen, ScrollText, ShieldCheck, Sun, TableProperties, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { api } from './api'
import { Button, ErrorState, Skeleton } from './components'
import { cn } from './lib'

const navigation = [
  { to: '/', label: 'Overview', icon: Activity },
  { to: '/tables', label: 'Tables', icon: TableProperties },
  { to: '/policies', label: 'Policies', icon: FileSliders },
  { to: '/operations', label: 'Operations', icon: ShieldCheck },
  { to: '/connections', label: 'Connections', icon: Cable },
  { to: '/audit', label: 'Audit & settings', icon: ScrollText },
]

export function AppShell() {
  const [collapsed, setCollapsed] = useState(false)
  const [mobileOpen, setMobileOpen] = useState(false)
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))
  const location = useLocation()
  const bootstrap = useQuery({ queryKey: ['bootstrap'], queryFn: api.bootstrap })

  useEffect(() => setMobileOpen(false), [location.pathname])

  function toggleTheme() {
    const next = !dark
    setDark(next)
    document.documentElement.classList.toggle('dark', next)
    document.documentElement.dataset.theme = next ? 'dark' : 'light'
    document.documentElement.style.colorScheme = next ? 'dark' : 'light'
    localStorage.setItem('fiq-theme', next ? 'dark' : 'light')
  }

  if (bootstrap.isPending) return <main className="mx-auto max-w-5xl p-6"><Skeleton rows={6} /></main>
  if (bootstrap.isError) return <main className="mx-auto max-w-3xl p-6"><ErrorState error={bootstrap.error} retry={() => void bootstrap.refetch()} /></main>

  const context = bootstrap.data
  return (
    <div className="flex h-dvh overflow-hidden bg-sidebar p-0 lg:p-2">
      <a href="#main-content" className="sr-only z-50 rounded-lg bg-primary px-3 py-2 text-primary-foreground focus:not-sr-only">Skip to content</a>
      {mobileOpen ? <button aria-label="Close navigation" className="fixed inset-0 z-30 bg-foreground/30 lg:hidden" onClick={() => setMobileOpen(false)} /> : null}
      <aside className={cn('fixed inset-y-0 left-0 z-40 flex w-72 flex-col border-r bg-sidebar transition-transform lg:static lg:z-auto lg:border-0', mobileOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0', collapsed && 'lg:w-12', !collapsed && 'lg:w-64')}>
        <div className="flex h-14 items-center gap-2 px-3">
          <div className="grid size-7 shrink-0 place-items-center rounded-lg bg-foreground text-xs font-bold text-background">F</div>
          {!collapsed ? <div className="min-w-0"><div className="text-sm font-semibold">FIQ</div><div className="truncate text-[11px] text-muted-foreground">Delta maintenance</div></div> : null}
          <Button aria-label="Close navigation" className="ml-auto size-8 px-0 lg:hidden" onClick={() => setMobileOpen(false)} variant="ghost"><X className="size-4" /></Button>
        </div>
        <nav aria-label="Primary navigation" className="flex-1 space-y-1 px-2 py-2">
          {navigation.map(({ to, label, icon: Icon }) => <NavLink className={({ isActive }) => cn('flex h-9 items-center gap-2 rounded-lg px-2.5 text-[13px] font-medium text-muted-foreground hover:bg-sidebar-accent hover:text-sidebar-foreground', isActive && 'bg-sidebar-accent text-sidebar-foreground', collapsed && 'justify-center px-0')} end={to === '/'} key={to} title={collapsed ? label : undefined} to={to}><Icon className="size-4 shrink-0" strokeWidth={1.5} />{!collapsed ? label : null}</NavLink>)}
        </nav>
        <div className="border-t p-2">
          {!collapsed ? <div className="mb-2 rounded-lg bg-sidebar-accent p-2.5"><div className="truncate text-xs font-medium">{context.workspace.name}</div><div className="mt-0.5 flex items-center gap-1.5 text-[11px] text-muted-foreground"><span className={cn('size-1.5 rounded-full', context.environment.production ? 'bg-destructive' : 'bg-success')} />{context.environment.name} · {context.role}</div></div> : null}
          <Button aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} className="hidden w-full lg:inline-flex" onClick={() => setCollapsed((value) => !value)} variant="ghost">{collapsed ? <PanelLeftOpen className="size-4" /> : <><PanelLeftClose className="size-4" />Collapse</>}</Button>
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden rounded-none border-0 bg-background shadow-sm lg:rounded-xl lg:border">
        <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3 sm:px-4">
          <Button aria-label="Open navigation" className="size-8 px-0 lg:hidden" onClick={() => setMobileOpen(true)} variant="ghost"><Menu className="size-4" /></Button>
          <Database className="size-3.5 text-muted-foreground" strokeWidth={1.5} />
          <span className="truncate text-xs font-medium">All catalogs</span>
          <span className="text-muted-foreground">/</span>
          <span className="text-xs text-muted-foreground">{context.environment.name}</span>
          {context.environment.production ? <span className="rounded-md bg-destructive/10 px-1.5 py-0.5 text-[10px] font-semibold text-destructive">PROD</span> : null}
          <div className="ml-auto flex items-center gap-1">
            <span className="hidden text-[11px] text-muted-foreground sm:inline">{context.timezone}</span>
            <Button aria-label={`Switch to ${dark ? 'light' : 'dark'} theme`} className="size-8 px-0" onClick={toggleTheme} variant="ghost">{dark ? <Sun className="size-4" /> : <Moon className="size-4" />}</Button>
          </div>
        </header>
        <main className="min-w-0 flex-1 overflow-y-auto" id="main-content"><div className="mx-auto w-full max-w-[1600px] space-y-6 p-3 sm:p-4"><Outlet /></div></main>
      </div>
    </div>
  )
}
