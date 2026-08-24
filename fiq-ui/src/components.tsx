import type { LucideIcon } from 'lucide-react'
import { AlertTriangle, Inbox, LoaderCircle, RefreshCw } from 'lucide-react'
import type { ButtonHTMLAttributes, PropsWithChildren, ReactNode } from 'react'
import { ApiError } from './api'
import { cn } from './lib'

export function Button({
  className,
  variant = 'primary',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'outline' | 'ghost' | 'danger' }) {
  return (
    <button
      className={cn(
        'inline-flex h-8 items-center justify-center gap-1.5 rounded-lg px-3 text-[13px] font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50',
        variant === 'primary' && 'bg-primary text-primary-foreground hover:opacity-90',
        variant === 'outline' && 'border bg-card hover:bg-accent',
        variant === 'ghost' && 'hover:bg-accent',
        variant === 'danger' && 'bg-destructive text-destructive-foreground hover:opacity-90',
        className,
      )}
      {...props}
    />
  )
}

export function Card({ className, children }: PropsWithChildren<{ className?: string }>) {
  return <section className={cn('rounded-xl border border-border/60 bg-card p-4', className)}>{children}</section>
}

export function PageHeader({ title, description, actions }: { title: string; description: string; actions?: ReactNode }) {
  return (
    <header className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
      <div className="min-w-0 sm:flex-1">
        <h1 className="text-xl font-semibold tracking-tight sm:text-2xl">{title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{description}</p>
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2 overflow-x-auto pb-1">{actions}</div> : null}
    </header>
  )
}

export function KpiCard({ icon: Icon, label, value, context, tone = 'default' }: {
  icon: LucideIcon
  label: string
  value: ReactNode
  context: string
  tone?: 'default' | 'warning' | 'danger' | 'success'
}) {
  return (
    <Card className="data-card">
      <div className="flex items-center gap-2 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
        <Icon className={cn('size-3.5', tone === 'warning' && 'text-warning', tone === 'danger' && 'text-destructive', tone === 'success' && 'text-success')} strokeWidth={1.5} />
        {label}
      </div>
      <div className="mt-3 text-2xl font-bold tracking-tight tabular-nums">{value}</div>
      <p className="mt-1 text-xs text-muted-foreground">{context}</p>
    </Card>
  )
}

export function Status({ value }: { value: string }) {
  const tone = ['FAILED', 'CRITICAL', 'ERROR'].includes(value)
    ? 'text-destructive'
    : ['WARNING', 'AWAITING_APPROVAL', 'PARTIAL', 'CANCELLING'].includes(value)
      ? 'text-warning'
      : ['SUCCEEDED', 'HEALTHY', 'COMPLETE'].includes(value)
        ? 'text-success'
        : 'text-muted-foreground'
  return <span className={cn('inline-flex items-center gap-1.5 text-xs font-medium', tone)}><span className="size-1.5 rounded-full bg-current" aria-hidden="true" />{value.replaceAll('_', ' ')}</span>
}

export function Skeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="space-y-3" aria-label="Loading">
      {Array.from({ length: rows }, (_, index) => <div className="h-12 animate-pulse rounded-xl bg-muted" key={index} />)}
    </div>
  )
}

export function EmptyState({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return (
    <div className="flex min-h-52 flex-col items-center justify-center rounded-xl border border-dashed p-6 text-center">
      <div className="grid size-12 place-items-center rounded-xl bg-muted"><Inbox className="size-5 text-muted-foreground" strokeWidth={1.5} /></div>
      <h2 className="mt-3 text-base font-semibold">{title}</h2>
      <p className="mt-1 max-w-md text-sm text-muted-foreground">{description}</p>
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  )
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const detail = error instanceof ApiError ? error.problem.detail : error instanceof Error ? error.message : 'The request failed.'
  const permission = error instanceof ApiError && error.problem.status === 403
  return (
    <div className="flex min-h-52 flex-col items-center justify-center rounded-xl border p-6 text-center">
      <div className="grid size-12 place-items-center rounded-xl bg-destructive/10"><AlertTriangle className="size-5 text-destructive" strokeWidth={1.5} /></div>
      <h2 className="mt-3 text-base font-semibold">{permission ? 'Permission required' : 'Unable to load this view'}</h2>
      <p className="mt-1 max-w-lg text-sm text-muted-foreground">{detail}</p>
      {retry ? <Button className="mt-4" onClick={retry} variant="outline"><RefreshCw className="size-3.5" />Retry</Button> : null}
    </div>
  )
}

export function InlineSpinner({ label = 'Working' }: { label?: string }) {
  return <span className="inline-flex items-center gap-1.5"><LoaderCircle className="size-3.5 animate-spin" />{label}</span>
}

export function DefinitionList({ values }: { values: Array<[string, ReactNode]> }) {
  return <dl className="divide-y">{values.map(([term, description]) => <div className="grid gap-1 py-2.5 sm:grid-cols-[180px_1fr]" key={term}><dt className="text-xs text-muted-foreground">{term}</dt><dd className="min-w-0 break-words text-sm">{description}</dd></div>)}</dl>
}

