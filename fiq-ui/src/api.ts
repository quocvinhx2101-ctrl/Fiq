import type {
  ApiProblem,
  AuditEvent,
  Bootstrap,
  Connection,
  HealthView,
  Operation,
  OperationType,
  Overview,
  Page,
  Policy,
  TableDetail,
  TableSummary,
} from './types'

const defaultWorkspace = '00000000-0000-0000-0000-000000000001'

export class ApiError extends Error {
  constructor(readonly problem: ApiProblem) {
    super(problem.detail)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-FIQ-Workspace': localStorage.getItem('fiq-workspace') ?? defaultWorkspace,
      ...init?.headers,
    },
  })
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({
      type: 'about:blank',
      title: response.statusText,
      status: response.status,
      detail: 'FIQ could not complete the request.',
      instance: path,
      timestamp: new Date().toISOString(),
      code: 'FIQ_HTTP_ERROR',
    }))) as ApiProblem
    throw new ApiError(problem)
  }
  return (await response.json()) as T
}

export const api = {
  bootstrap: () => request<Bootstrap>('/api/v1/bootstrap'),
  overview: () => request<Overview>('/api/v1/overview'),
  tables: (search = '') => request<Page<TableSummary>>(`/api/v1/tables?limit=100&search=${encodeURIComponent(search)}`),
  table: (id: string) => request<TableDetail>(`/api/v1/tables/${id}`),
  health: (id: string) => request<HealthView>(`/api/v1/tables/${id}/health`),
  refreshHealth: (id: string) => request<HealthView>(`/api/v1/tables/${id}/health/refresh`, { method: 'POST' }),
  policies: () => request<Page<Policy>>('/api/v1/policies?limit=200'),
  operations: () => request<Page<Operation>>('/api/v1/operations?limit=200'),
  operation: (id: string) => request<Operation>(`/api/v1/operations/${id}`),
  plan: (tableId: string, policyId: string, operationType: OperationType) =>
    request<Operation>('/api/v1/operations/plan', {
      method: 'POST',
      body: JSON.stringify({ tableId, policyId, operationType, idempotencyKey: crypto.randomUUID() }),
    }),
  execute: (id: string) => request<Operation>(`/api/v1/operations/${id}/execute`, { method: 'POST' }),
  approve: (id: string, decision: 'APPROVE' | 'REJECT', comment = '') =>
    request<Operation>(`/api/v1/operations/${id}/approval`, {
      method: 'POST',
      body: JSON.stringify({ decision, comment }),
    }),
  cancel: (id: string) => request<Operation>(`/api/v1/operations/${id}/cancel`, { method: 'POST' }),
  retry: (id: string) => request<Operation>(`/api/v1/operations/${id}/retry`, { method: 'POST' }),
  connections: () => request<Connection[]>('/api/v1/connections'),
  testConnection: (id: string) => request<{ reachable: boolean; status: string; message: string; capabilities: Record<string, unknown> }>(`/api/v1/connections/${id}/test`, { method: 'POST' }),
  createConnection: (value: Omit<Connection, 'id' | 'enabled' | 'lastTestedAt' | 'lastTestStatus' | 'lastTestMessage'>) =>
    request<Connection>('/api/v1/connections', {
      method: 'POST',
      body: JSON.stringify({ ...value, options: {} }),
    }),
  audit: () => request<Page<AuditEvent>>('/api/v1/audit?limit=200'),
}
