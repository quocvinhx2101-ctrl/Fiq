import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Activity, AlertTriangle, ArrowRight, Boxes, CalendarClock, CheckCircle2, CircleDollarSign,
  Clock3, Database, ExternalLink, FileArchive, FileWarning, Filter, Gauge, History, KeyRound,
  ListChecks, PauseCircle, Play, Plus, RefreshCw, Search, Server, Settings2, ShieldAlert,
  ShieldCheck, TableProperties, TimerReset, Trash2, Webhook,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from './api'
import { Button, Card, DefinitionList, EmptyState, ErrorState, InlineSpinner, KpiCard, PageHeader, Skeleton, Status } from './components'
import { cn, formatBytes as bytes, formatDate as dateTime } from './lib'
import type { Connection, HealthView, Operation, OperationState, OperationType, Policy, TableSummary } from './types'

const integer = (value: number) => new Intl.NumberFormat().format(value)

function QueryState({ query, children }: { query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown }; children: React.ReactNode }) {
  if (query.isPending) return <Skeleton rows={6} />
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />
  return children
}

function StaleNote({ fetching, failed, refreshedAt }: { fetching: boolean; failed: boolean; refreshedAt?: string }) {
  if (!fetching && !failed) return null
  return <div className={cn('flex items-center gap-2 rounded-lg border px-3 py-2 text-xs', failed ? 'border-warning/40 bg-warning/5 text-warning' : 'bg-muted text-muted-foreground')}>
    {fetching ? <InlineSpinner label="Refreshing while the last known data remains visible" /> : <><AlertTriangle className="size-3.5" />Refresh failed. Showing data from {dateTime(refreshedAt)}.</>}
  </div>
}

export function OverviewPage() {
  const query = useQuery({ queryKey: ['overview'], queryFn: api.overview, refetchInterval: 30_000 })
  const operations = useQuery({ queryKey: ['operations'], queryFn: api.operations, refetchInterval: 10_000 })
  return <QueryState query={query}><div className="space-y-6">
    <PageHeader title="Maintenance overview" description="Fleet health, risk, approvals, and active Delta maintenance in one operational view." actions={<Button onClick={() => void query.refetch()} variant="outline"><RefreshCw className="size-3.5" />Refresh</Button>} />
    {query.data ? <>
      <StaleNote failed={query.isRefetchError} fetching={query.isFetching} refreshedAt={query.data.refreshedAt} />
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard icon={Database} label="Managed tables" value={integer(query.data.tables)} context={`${integer(query.data.warningTables)} warning · ${integer(query.data.criticalTables)} critical`} />
        <KpiCard icon={Activity} label="Active operations" value={integer(query.data.activeOperations)} context={`${integer(query.data.failedOperations)} failed in current view`} tone={query.data.failedOperations ? 'danger' : 'success'} />
        <KpiCard icon={ShieldCheck} label="Pending approvals" value={integer(query.data.pendingApprovals)} context="Safety gates waiting for a decision" tone={query.data.pendingApprovals ? 'warning' : 'success'} />
        <KpiCard icon={FileArchive} label="Reclaimable storage" value={bytes(query.data.reclaimableBytes)} context="Estimated from the latest assessments" />
      </div>
      <div className="grid gap-4 xl:grid-cols-[1.5fr_1fr]">
        <Card>
          <div className="flex items-center justify-between"><div><h2 className="font-semibold">Fleet posture</h2><p className="mt-0.5 text-xs text-muted-foreground">Latest assessment distribution</p></div><Link className="text-xs font-medium text-primary hover:underline" to="/tables">Explore tables</Link></div>
          <div className="mt-5 h-64" aria-label="Fleet health bar chart"><ResponsiveContainer width="100%" height="100%"><BarChart data={[{ name: 'Healthy', count: Math.max(0, query.data.tables - query.data.warningTables - query.data.criticalTables) }, { name: 'Warning', count: query.data.warningTables }, { name: 'Critical', count: query.data.criticalTables }]}><CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} /><XAxis dataKey="name" fontSize={11} tickLine={false} /><YAxis allowDecimals={false} fontSize={11} tickLine={false} width={28} /><Tooltip contentStyle={{ background: 'var(--popover)', border: '1px solid var(--border)', borderRadius: 8, fontSize: 12 }} /><Bar dataKey="count" fill="var(--primary)" radius={[5, 5, 0, 0]} /></BarChart></ResponsiveContainer></div>
        </Card>
        <Card>
          <div className="flex items-center justify-between"><div><h2 className="font-semibold">Recent operations</h2><p className="mt-0.5 text-xs text-muted-foreground">Live scheduler state</p></div><Link className="text-xs font-medium text-primary hover:underline" to="/operations">View queue</Link></div>
          <div className="mt-4 divide-y">
            {operations.data?.items.slice(0, 6).map((operation) => <Link className="flex items-center gap-3 py-3 hover:bg-accent/50" key={operation.id} to={`/operations?selected=${operation.id}`}><span className="grid size-8 shrink-0 place-items-center rounded-lg bg-muted"><Activity className="size-4" /></span><span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium">{operation.tableName}</span><span className="block truncate text-xs text-muted-foreground">{operation.operationType.replaceAll('_', ' ')}</span></span><Status value={operation.state} /></Link>)}
            {operations.data?.items.length === 0 ? <p className="py-10 text-center text-sm text-muted-foreground">No operations have been planned.</p> : null}
            {operations.isError ? <p className="py-4 text-xs text-destructive">Operations could not be refreshed.</p> : null}
          </div>
        </Card>
      </div>
    </> : null}
  </div></QueryState>
}

function TableCard({ table }: { table: TableSummary }) {
  return <Link className="block rounded-xl border bg-card p-4 hover:border-primary/40" to={`/tables/${table.id}`}><div className="flex items-start justify-between gap-3"><div className="min-w-0"><p className="truncate font-mono text-xs font-semibold">{table.qualifiedName}</p><p className="mt-1 text-xs text-muted-foreground">{table.catalog} · v{table.currentVersion}</p></div><Status value={table.severity} /></div><dl className="mt-4 grid grid-cols-3 gap-3 text-xs"><div><dt className="text-muted-foreground">Files</dt><dd className="mt-1 font-semibold tabular-nums">{integer(table.fileCount)}</dd></div><div><dt className="text-muted-foreground">Size</dt><dd className="mt-1 font-semibold">{bytes(table.totalBytes)}</dd></div><div><dt className="text-muted-foreground">Debt</dt><dd className="mt-1 font-semibold tabular-nums">{table.debtScore.toFixed(0)}</dd></div></dl>{table.readOnlyReason ? <p className="mt-3 rounded-md bg-warning/10 px-2 py-1.5 text-[11px] text-warning">Read-only: {table.readOnlyReason}</p> : null}</Link>
}

export function TablesPage() {
  const [search, setSearch] = useState('')
  const [mode, setMode] = useState('ALL')
  const query = useQuery({ queryKey: ['tables', search], queryFn: () => api.tables(search) })
  const rows = useMemo(() => query.data?.items.filter((table) => mode === 'ALL' || table.accessMode === mode) ?? [], [query.data, mode])
  return <div className="space-y-6"><PageHeader title="Tables" description="Search the Delta fleet, inspect maintenance debt, and review operation capabilities." actions={<Button disabled title="Discovery is initiated from a tested connection; this endpoint is not enabled yet." variant="outline"><Plus className="size-3.5" />Discover tables</Button>} />
    <Card className="p-3"><div className="flex flex-col gap-2 sm:flex-row"><label className="relative flex-1"><Search className="absolute left-2.5 top-2.5 size-3.5 text-muted-foreground" /><span className="sr-only">Search tables</span><input className="h-9 w-full rounded-lg border bg-background pl-8 pr-3 text-sm" onChange={(event) => setSearch(event.target.value)} placeholder="Search catalog, schema, or table" value={search} /></label><label className="flex items-center gap-2"><Filter className="size-3.5 text-muted-foreground" /><span className="sr-only">Access mode</span><select className="h-9 rounded-lg border bg-background px-3 text-sm" onChange={(event) => setMode(event.target.value)} value={mode}><option value="ALL">All access modes</option><option value="CLASSIC">Classic</option><option value="CATALOG_MANAGED">Catalog managed</option></select></label></div></Card>
    <QueryState query={query}>{query.data ? <><StaleNote failed={query.isRefetchError} fetching={query.isFetching} />{rows.length ? <><div className="grid gap-3 md:hidden">{rows.map((table) => <TableCard key={table.id} table={table} />)}</div><Card className="hidden overflow-hidden p-0 md:block"><div className="overflow-x-auto"><table><thead><tr><th>Table</th><th>Access</th><th className="text-right">Files</th><th className="text-right">Size</th><th className="text-right">Debt</th><th>Health</th><th>Refreshed</th><th><span className="sr-only">Open</span></th></tr></thead><tbody>{rows.map((table) => <tr key={table.id}><td><div className="max-w-md truncate font-mono text-xs font-medium">{table.qualifiedName}</div><div className="mt-0.5 text-[11px] text-muted-foreground">{table.catalog} · version {table.currentVersion}</div></td><td className="text-xs">{table.accessMode.replace('_', ' ')}</td><td className="text-right tabular-nums">{integer(table.fileCount)}</td><td className="text-right tabular-nums">{bytes(table.totalBytes)}</td><td className="text-right tabular-nums">{table.debtScore.toFixed(0)}</td><td><Status value={table.severity} /></td><td className="text-xs text-muted-foreground">{dateTime(table.refreshedAt)}</td><td><Link aria-label={`Open ${table.qualifiedName}`} className="inline-flex size-8 items-center justify-center rounded-lg hover:bg-accent" to={`/tables/${table.id}`}><ArrowRight className="size-4" /></Link></td></tr>)}</tbody></table></div></Card></> : <EmptyState title={query.data.items.length ? 'No tables match these filters' : 'No Delta tables discovered'} description={query.data.items.length ? 'Clear or change a filter to restore the catalog view.' : 'Create and test a connection, then run discovery to populate this workspace.'} />}</> : null}</QueryState>
  </div>
}

type TableTab = 'Overview' | 'Files' | 'Deletion vectors' | 'Transaction log' | 'History' | 'Policies'
const tableTabs: TableTab[] = ['Overview', 'Files', 'Deletion vectors', 'Transaction log', 'History', 'Policies']

export function TableDetailPage() {
  const { tableId = '' } = useParams()
  const [tab, setTab] = useState<TableTab>('Overview')
  const queryClient = useQueryClient()
  const detail = useQuery({ queryKey: ['table', tableId], queryFn: () => api.table(tableId) })
  const health = useQuery({ queryKey: ['health', tableId], queryFn: () => api.health(tableId), retry: false })
  const refresh = useMutation({ mutationFn: () => api.refreshHealth(tableId), onSuccess: (value) => { queryClient.setQueryData(['health', tableId], value); void queryClient.invalidateQueries({ queryKey: ['table', tableId] }) } })
  const policies = useQuery({ queryKey: ['policies'], queryFn: api.policies })
  return <QueryState query={detail}>{detail.data ? <div className="space-y-5">
    <PageHeader title={detail.data.summary.qualifiedName} description={`${detail.data.summary.accessMode.replace('_', ' ')} Delta table · version ${detail.data.summary.currentVersion}`} actions={<Button disabled={refresh.isPending} onClick={() => refresh.mutate()} variant="outline">{refresh.isPending ? <InlineSpinner label="Assessing" /> : <><RefreshCw className="size-3.5" />Refresh assessment</>}</Button>} />
    {refresh.isError ? <ErrorState error={refresh.error} retry={() => refresh.mutate()} /> : null}
    {detail.data.summary.readOnlyReason ? <div className="flex gap-2 rounded-lg border border-warning/30 bg-warning/5 p-3 text-xs text-warning"><ShieldAlert className="mt-0.5 size-4 shrink-0" />{detail.data.summary.readOnlyReason}</div> : null}
    <div className="scrollbar-hide flex overflow-x-auto border-b" role="tablist">{tableTabs.map((value) => <button aria-selected={tab === value} className={cn('shrink-0 border-b-2 px-3 py-2 text-xs font-medium', tab === value ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground')} key={value} onClick={() => setTab(value)} role="tab">{value}</button>)}</div>
    {tab === 'Overview' ? <TableOverview detail={detail.data} health={health.data} /> : null}
    {tab === 'Files' ? <MetricPanel icon={Boxes} title="File layout" health={health} values={health.data?.fileLayout} /> : null}
    {tab === 'Deletion vectors' ? <MetricPanel icon={Trash2} title="Deletion vectors" health={health} values={health.data?.deletionVectors} /> : null}
    {tab === 'Transaction log' ? <MetricPanel icon={History} title="Transaction log" health={health} values={health.data?.transactionLog} /> : null}
    {tab === 'History' ? <Card><EmptyState title="Spark history is not loaded" description="History requires the configured Spark/Livy engine. FIQ does not infer commit history from incomplete filesystem visibility." /></Card> : null}
    {tab === 'Policies' ? <TablePolicyPanel tableId={tableId} policies={policies.data?.items ?? []} capabilities={detail.data.capabilities} /> : null}
  </div> : null}</QueryState>
}

function TableOverview({ detail, health }: { detail: Awaited<ReturnType<typeof api.table>>; health: HealthView | undefined }) {
  return <div className="grid gap-4 xl:grid-cols-[1.3fr_1fr]"><Card><h2 className="font-semibold">Table identity</h2><DefinitionList values={[
    ['Location', detail.location ?? 'Catalog managed — no path access'], ['Catalog', detail.summary.catalog], ['Namespace', detail.namespace.join('.') || '—'], ['Partition columns', detail.partitionColumns.join(', ') || 'None'], ['Clustering columns', detail.clusteringColumns.join(', ') || 'None'], ['Table features', [...detail.summary.features].join(', ') || 'None'], ['Health completeness', <Status value={detail.summary.healthCompleteness} />],
  ]} /></Card><Card><h2 className="font-semibold">Maintenance capabilities</h2><div className="mt-3 divide-y">{Object.entries(detail.capabilities).map(([operation, capability]) => <div className="flex items-start justify-between gap-3 py-2.5" key={operation}><div><p className="text-xs font-medium">{operation.replaceAll('_', ' ')}</p><p className="mt-0.5 text-[11px] text-muted-foreground">{capability.reason}</p></div><Status value={capability.supported ? capability.approvalRequired ? 'AWAITING_APPROVAL' : 'SUPPORTED' : 'BLOCKED'} /></div>)}</div></Card>{health ? <Card className="xl:col-span-2"><div className="flex items-center justify-between"><div><h2 className="font-semibold">Assessment issues</h2><p className="mt-0.5 text-xs text-muted-foreground">Observed version {health.observedVersion} · {health.provenance}</p></div><span className="text-2xl font-bold tabular-nums">{health.debtScore.toFixed(0)}</span></div><div className="mt-4 grid gap-3 md:grid-cols-2">{health.issues.map((issue, index) => <div className="rounded-lg border p-3" key={`${issue.code}-${index}`}><Status value={issue.severity ?? 'INFO'} /><p className="mt-2 text-sm font-medium">{issue.title ?? issue.code}</p><p className="mt-1 text-xs text-muted-foreground">{issue.detail ?? issue.message}</p></div>)}</div>{health.issues.length === 0 ? <p className="mt-4 text-sm text-muted-foreground">No maintenance issues were detected.</p> : null}</Card> : null}</div>
}

function MetricPanel({ icon: Icon, title, health, values }: { icon: typeof Boxes; title: string; health: ReturnType<typeof useQuery<HealthView>>; values: Record<string, unknown> | undefined }) {
  return <QueryState query={health}><Card><div className="flex items-center gap-2"><Icon className="size-4" /><h2 className="font-semibold">{title}</h2></div>{values ? <DefinitionList values={Object.entries(values).map(([key, value]) => [key.replaceAll('_', ' '), typeof value === 'number' ? integer(value) : String(value ?? '—')])} /> : <p className="mt-4 text-sm text-muted-foreground">No metrics available.</p>}</Card></QueryState>
}

function TablePolicyPanel({ tableId, policies, capabilities }: { tableId: string; policies: Policy[]; capabilities: Awaited<ReturnType<typeof api.table>>['capabilities'] }) {
  const client = useQueryClient()
  const [policyId, setPolicyId] = useState(policies[0]?.id ?? '')
  const [operation, setOperation] = useState<OperationType>('OPTIMIZE_BINPACK')
  const plan = useMutation({ mutationFn: () => api.plan(tableId, policyId, operation), onSuccess: () => void client.invalidateQueries({ queryKey: ['operations'] }) })
  const capability = capabilities[operation]
  return <Card><h2 className="font-semibold">Plan maintenance</h2><p className="mt-1 text-xs text-muted-foreground">Assess → review risk and cost → approve if required → execute → verify.</p><div className="mt-4 grid gap-3 md:grid-cols-2"><label className="text-xs font-medium">Policy<select className="mt-1 block h-9 w-full rounded-lg border bg-background px-3 text-sm" onChange={(event) => setPolicyId(event.target.value)} value={policyId}><option value="">Select a policy</option>{policies.map((policy) => <option key={policy.id} value={policy.id}>{policy.name}</option>)}</select></label><label className="text-xs font-medium">Operation<select className="mt-1 block h-9 w-full rounded-lg border bg-background px-3 text-sm" onChange={(event) => setOperation(event.target.value as OperationType)} value={operation}>{Object.keys(capabilities).map((value) => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}</select></label></div><div className={cn('mt-4 rounded-lg border p-3 text-xs', capability?.supported ? 'bg-muted/40' : 'border-warning/30 bg-warning/5')}><p className="font-medium">{capability?.supported ? capability.approvalRequired ? 'Approval required' : 'Ready to plan' : 'Operation unavailable'}</p><p className="mt-1 text-muted-foreground">{capability?.reason ?? 'Capability was not reported by the backend.'}</p></div>{plan.isError ? <p className="mt-3 text-xs text-destructive">{plan.error.message}</p> : null}{plan.data ? <div className="mt-3 rounded-lg border border-success/30 bg-success/5 p-3 text-xs"><p className="font-medium text-success">Plan created</p><p className="mt-1 font-mono">{plan.data.commandPreview}</p><Link className="mt-2 inline-flex items-center gap-1 font-medium text-primary" to="/operations">Review operation <ArrowRight className="size-3" /></Link></div> : null}<Button className="mt-4" disabled={!policyId || !capability?.supported || plan.isPending} onClick={() => plan.mutate()} title={!capability?.supported ? capability?.reason : undefined}>{plan.isPending ? <InlineSpinner label="Planning" /> : <><ListChecks className="size-3.5" />Create plan</>}</Button></Card>
}

export function PoliciesPage() {
  const query = useQuery({ queryKey: ['policies'], queryFn: api.policies })
  return <div className="space-y-6"><PageHeader title="Policies" description="Selectors, schedules, safety thresholds, budgets, and approval rules for maintenance." actions={<Button disabled title="Policy mutation is disabled until the validation/simulation API is available."><Plus className="size-3.5" />New policy</Button>} /><QueryState query={query}>{query.data?.items.length ? <div className="grid gap-3 lg:grid-cols-2">{query.data.items.map((policy) => <Card key={policy.id}><div className="flex items-start justify-between gap-3"><div><h2 className="font-semibold">{policy.name}</h2><p className="mt-1 text-xs text-muted-foreground">{policy.description || 'No description'}</p></div><Status value={policy.enabled ? 'ENABLED' : 'DISABLED'} /></div><DefinitionList values={[["Schedule", `${policy.cron} · ${policy.timezone}`], ['Operations', policy.operations.map((value) => value.replaceAll('_', ' ')).join(', ')], ['Byte budget', bytes(policy.maxBytesPerRun)], ['Concurrency', policy.maxConcurrentOperations], ['Updated', dateTime(policy.updatedAt)]]} /><div className="flex gap-2"><Button disabled title="The mutation endpoint is not enabled." variant="outline"><Settings2 className="size-3.5" />Edit</Button><Button disabled title="Simulation requires the policy validation endpoint." variant="ghost">Simulate</Button></div></Card>)}</div> : <EmptyState title="No maintenance policies" description="Policies are workspace scoped and must pass server-side validation before they can schedule operations." />}</QueryState></div>
}

const activeStates: OperationState[] = ['PLANNED', 'AWAITING_APPROVAL', 'QUEUED', 'RUNNING', 'CANCELLING']
function OperationActions({ operation }: { operation: Operation }) {
  const client = useQueryClient()
  const [busy, setBusy] = useState('')
  const act = async (name: string, action: () => Promise<unknown>) => { setBusy(name); try { await action(); await client.invalidateQueries({ queryKey: ['operations'] }) } finally { setBusy('') } }
  return <div className="flex flex-wrap gap-2">{operation.state === 'PLANNED' ? <Button disabled={!!busy} onClick={() => void act('execute', () => api.execute(operation.id))}>{busy === 'execute' ? <InlineSpinner /> : <><Play className="size-3.5" />Queue</>}</Button> : null}{operation.state === 'AWAITING_APPROVAL' ? <><Button disabled={!!busy} onClick={() => void act('approve', () => api.approve(operation.id, 'APPROVE'))}>{busy === 'approve' ? <InlineSpinner /> : <><ShieldCheck className="size-3.5" />Approve</>}</Button><Button disabled={!!busy} onClick={() => void act('reject', () => api.approve(operation.id, 'REJECT'))} variant="outline">Reject</Button></> : null}{['QUEUED', 'RUNNING'].includes(operation.state) ? <Button disabled={!!busy} onClick={() => void act('cancel', () => api.cancel(operation.id))} variant="danger">{busy === 'cancel' ? <InlineSpinner /> : <><PauseCircle className="size-3.5" />Cancel</>}</Button> : null}{['FAILED', 'SKIPPED', 'CANCELLED'].includes(operation.state) ? <Button disabled={!!busy} onClick={() => void act('retry', () => api.retry(operation.id))} variant="outline">{busy === 'retry' ? <InlineSpinner label="Reassessing" /> : <><TimerReset className="size-3.5" />Retry safely</>}</Button> : null}</div>
}

export function OperationsPage() {
  const [state, setState] = useState('ALL')
  const query = useQuery({ queryKey: ['operations'], queryFn: api.operations, refetchInterval: 5_000 })
  const rows = query.data?.items.filter((operation) => state === 'ALL' || state === 'ACTIVE' && activeStates.includes(operation.state) || operation.state === state) ?? []
  return <div className="space-y-6"><PageHeader title="Operations" description="Operation-scoped queue with approvals, Spark evidence, cancellation, and post-run verification." actions={<Button onClick={() => void query.refetch()} variant="outline"><RefreshCw className="size-3.5" />Refresh</Button>} /><div className="flex gap-2 overflow-x-auto">{['ALL', 'ACTIVE', 'AWAITING_APPROVAL', 'FAILED', 'SUCCEEDED'].map((value) => <button className={cn('h-8 shrink-0 rounded-lg border px-3 text-xs font-medium', state === value ? 'border-primary bg-primary text-primary-foreground' : 'bg-card')} key={value} onClick={() => setState(value)}>{value.replaceAll('_', ' ')}</button>)}</div><QueryState query={query}>{rows.length ? <div className="space-y-3">{rows.map((operation) => <Card key={operation.id}><div className="flex flex-col gap-4 lg:flex-row lg:items-start"><div className="min-w-0 flex-1"><div className="flex flex-wrap items-center gap-2"><Status value={operation.state} /><span className="text-xs text-muted-foreground">{dateTime(operation.plannedAt)}</span></div><h2 className="mt-2 truncate font-mono text-sm font-semibold">{operation.tableName}</h2><p className="mt-1 text-xs font-medium">{operation.operationType.replaceAll('_', ' ')}</p><pre className="mt-3 overflow-x-auto rounded-lg bg-muted p-3 font-mono text-[11px] text-muted-foreground">{operation.commandPreview}</pre>{operation.errorMessage ? <p className="mt-3 rounded-lg bg-destructive/10 p-3 text-xs text-destructive">{operation.errorCode}: {operation.errorMessage}</p> : null}<div className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-[11px] text-muted-foreground"><span>Version {operation.basedOnVersion}</span><span>{bytes(operation.estimatedBytes)} estimated</span><span>{operation.externalJobId ? `Livy ${operation.externalJobId}` : 'Not submitted'}</span></div></div><div className="shrink-0"><OperationActions operation={operation} /></div></div></Card>)}</div> : <EmptyState title="No operations in this view" description="Create a plan from a table's Policies tab, then review its command, cost, and safety gates here." />}</QueryState></div>
}

function ConnectionCard({ connection }: { connection: Connection }) {
  const client = useQueryClient()
  const test = useMutation({ mutationFn: () => api.testConnection(connection.id), onSuccess: () => void client.invalidateQueries({ queryKey: ['connections'] }) })
  return <Card><div className="flex items-start justify-between"><div className="flex gap-3"><span className="grid size-9 place-items-center rounded-lg bg-muted"><Server className="size-4" /></span><div><h2 className="font-semibold">{connection.name}</h2><p className="text-xs text-muted-foreground">{connection.catalogType} · {connection.engineType}</p></div></div><Status value={connection.lastTestStatus ?? (connection.enabled ? 'ENABLED' : 'DISABLED')} /></div><DefinitionList values={[["Catalog URI", connection.catalogUri ?? 'Not configured'], ['Warehouse', connection.warehouseUri ?? 'Not configured'], ['Engine', connection.engineUri ?? 'Not configured'], ['Credential', connection.secretRef ?? 'Cloud credential chain'], ['Last tested', dateTime(connection.lastTestedAt)]]} />{test.data ? <p className={cn('mb-3 rounded-lg p-2 text-xs', test.data.reachable ? 'bg-success/10 text-success' : 'bg-destructive/10 text-destructive')}>{test.data.message}</p> : null}{test.isError ? <p className="mb-3 text-xs text-destructive">{test.error.message}</p> : null}<div className="flex gap-2"><Button disabled={test.isPending} onClick={() => test.mutate()} variant="outline">{test.isPending ? <InlineSpinner label="Testing" /> : <><Gauge className="size-3.5" />Test capabilities</>}</Button><Button disabled title="Discovery job endpoint is not enabled." variant="ghost">Discover</Button></div></Card>
}

export function ConnectionsPage() {
  const query = useQuery({ queryKey: ['connections'], queryFn: api.connections })
  return <div className="space-y-6"><PageHeader title="Connections" description="Catalog, storage, and Spark execution configuration. Credentials remain external secret references." actions={<Button disabled title="Connection creation is available through the REST API; the guided secret-reference form is pending."><Plus className="size-3.5" />New connection</Button>} /><QueryState query={query}>{query.data?.length ? <div className="grid gap-3 lg:grid-cols-2">{query.data.map((connection: Connection) => <ConnectionCard connection={connection} key={connection.id} />)}</div> : <EmptyState title="No connections configured" description="An admin can register a catalog/storage connection and a Livy execution engine using secret references." />}</QueryState></div>
}

export function AuditSettingsPage() {
  const [tab, setTab] = useState<'Audit' | 'Access & integrations' | 'System'>('Audit')
  const audit = useQuery({ queryKey: ['audit'], queryFn: api.audit })
  return <div className="space-y-6"><PageHeader title="Audit & settings" description="Immutable activity evidence, access controls, automation credentials, and system state." /><div className="flex gap-1 border-b">{(['Audit', 'Access & integrations', 'System'] as const).map((value) => <button className={cn('border-b-2 px-3 py-2 text-xs font-medium', tab === value ? 'border-primary' : 'border-transparent text-muted-foreground')} key={value} onClick={() => setTab(value)}>{value}</button>)}</div>{tab === 'Audit' ? <QueryState query={audit}>{audit.data?.items.length ? <Card className="overflow-hidden p-0"><div className="overflow-x-auto"><table><thead><tr><th>Time</th><th>Event</th><th>Severity</th><th>Principal</th><th>Resource</th></tr></thead><tbody>{audit.data.items.map((event) => <tr key={event.id}><td className="whitespace-nowrap text-xs">{dateTime(event.occurredAt)}</td><td className="font-mono text-xs">{event.eventType}</td><td><Status value={event.severity} /></td><td className="text-xs">{event.principal ?? 'system'}</td><td className="text-xs text-muted-foreground">{event.resourceType ?? '—'} {event.resourceId ?? ''}</td></tr>)}</tbody></table></div></Card> : <EmptyState title="No audit events" description="Planning, approvals, execution, connections, and security changes are recorded here append-only." />}</QueryState> : null}{tab === 'Access & integrations' ? <div className="grid gap-3 lg:grid-cols-3"><SettingsCard icon={KeyRound} title="API keys" description="Hashed automation credentials scoped to a workspace and role." /><SettingsCard icon={Webhook} title="Webhooks" description="Signed operation and health notifications with delivery history." /><SettingsCard icon={ShieldCheck} title="Role bindings" description="OIDC groups mapped to viewer, operator, approver, and admin permissions." /></div> : null}{tab === 'System' ? <div className="grid gap-3 lg:grid-cols-3"><SettingsCard icon={CheckCircle2} title="Control plane" description="Quarkus readiness and liveness are exposed under /q/health." href="/q/health" /><SettingsCard icon={Activity} title="Metrics" description="Prometheus-compatible runtime and scheduler metrics." href="/q/metrics" /><SettingsCard icon={ExternalLink} title="OpenAPI" description="Generated REST contract and RFC 7807 error schemas." href="/q/openapi" /></div> : null}</div>
}

function SettingsCard({ icon: Icon, title, description, href }: { icon: typeof KeyRound; title: string; description: string; href?: string }) {
  return <Card><span className="grid size-9 place-items-center rounded-lg bg-muted"><Icon className="size-4" /></span><h2 className="mt-3 font-semibold">{title}</h2><p className="mt-1 min-h-10 text-xs text-muted-foreground">{description}</p>{href ? <a className="mt-4 inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline" href={href} rel="noreferrer" target="_blank">Open endpoint <ExternalLink className="size-3" /></a> : <Button className="mt-4" disabled title="Management endpoint is scheduled for the hardening phase." variant="outline">Manage</Button>}</Card>
}
