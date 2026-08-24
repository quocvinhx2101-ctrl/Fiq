export type Page<T> = { items: T[]; nextCursor: string | null }

export type Workspace = { id: string; name: string; timezone: string }
export type Environment = { id: string; name: string; production: boolean }
export type Bootstrap = {
  workspace: Workspace
  environment: Environment
  role: 'VIEWER' | 'OPERATOR' | 'APPROVER' | 'ADMIN' | 'NONE'
  timezone: string
  serverTime: string
  version: string
}

export type Overview = {
  tables: number
  warningTables: number
  criticalTables: number
  activeOperations: number
  failedOperations: number
  pendingApprovals: number
  reclaimableBytes: number
  refreshedAt: string
}

export type OperationType =
  | 'OPTIMIZE_BINPACK'
  | 'OPTIMIZE_ZORDER'
  | 'OPTIMIZE_CLUSTERING'
  | 'OPTIMIZE_FULL'
  | 'REORG_PURGE'
  | 'VACUUM_LITE'
  | 'VACUUM_FULL'
  | 'VACUUM_INVENTORY'

export type OperationState =
  | 'PLANNED'
  | 'AWAITING_APPROVAL'
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCELLING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'SKIPPED'

export type ExecutionTarget =
  | { type: 'PATH'; uri: string }
  | { type: 'CATALOG'; catalog: string; namespace: string[]; table: string }

export type TableSummary = {
  id: string
  qualifiedName: string
  executionTarget: ExecutionTarget
  sample: boolean
  discoveryStatus: 'ACTIVE' | 'MISSING'
  environment: string
  catalog: string
  accessMode: 'CLASSIC' | 'CATALOG_MANAGED'
  currentVersion: number
  fileCount: number
  totalBytes: number
  debtScore: number
  severity: string
  healthCompleteness: 'COMPLETE' | 'PARTIAL' | 'STALE'
  refreshedAt: string
  features: string[]
  readOnlyReason: string | null
}

export type Capability = { supported: boolean; approvalRequired: boolean; reason: string }
export type TableDetail = {
  summary: TableSummary
  location: string | null
  namespace: string[]
  partitionColumns: string[]
  clusteringColumns: string[]
  properties: Record<string, string>
  tags: Record<string, string>
  capabilities: Record<OperationType, Capability>
}

export type HealthView = {
  id: string
  tableId: string
  observedVersion: number
  assessedAt: string
  provenance: string
  completeness: 'COMPLETE' | 'PARTIAL' | 'STALE'
  staleReason: string | null
  dimensions: Record<string, {
    completeness: 'COMPLETE' | 'PARTIAL' | 'UNKNOWN' | 'STALE'
    provenance: string
    observedAt: string
    observedVersion: number
    incompleteReason: string | null
    facts?: Record<string, unknown>
  }>
  fileLayout: Record<string, number>
  deletionVectors: Record<string, number>
  transactionLog: Record<string, unknown>
  storageRetention: Record<string, number>
  clustering: Record<string, unknown>
  protocol: Record<string, unknown>
  debtScore: number
  issues: Array<Record<string, string>>
}

export type Policy = {
  id: string
  name: string
  description: string | null
  enabled: boolean
  cron: string
  timezone: string
  operations: OperationType[]
  maxBytesPerRun: number
  maxConcurrentOperations: number
  updatedAt: string
}

export type PolicyRequest = {
  name: string
  description: string
  enabled: boolean
  selector: {
    environmentGlob: string
    catalogGlob: string
    namespaceGlob: string
    tableGlob: string
    requiredTags: Record<string, string>
  }
  cron: string
  timezone: string
  maintenanceWindow: { days: string[]; start: string; end: string }
  operations: OperationType[]
  operationConfigs: Partial<Record<OperationType, {
    retentionHours: number
    zOrderColumns: string[]
    predicate: string
    inventoryTable: string
    automatic: boolean
    approvalRequired: boolean
    fileLayoutPolicy: {
      smallFileThresholdBytes: number
      minimumSmallFileCount: number
      minimumSmallFileRatio: number
      minimumRewriteBytes: number
      targetFileSizeBytes: number
      minimumExpectedReductionRatio: number
    }
    vacuumPolicy: { minimumCandidateCount: number; minimumReclaimableBytes: number }
  }>>
  maxBytesPerRun: number
  maxConcurrentOperations: number
  requireApprovalAboveBudget: boolean
}

export type Operation = {
  id: string
  workspaceId: string
  tableId: string
  tableName: string
  executionTarget: ExecutionTarget
  policyId: string
  operationType: OperationType
  state: OperationState
  basedOnVersion: number
  estimatedBytes: number
  approvalRequired: boolean
  approvedBy: string | null
  externalJobId: string | null
  commandPreview: string
  reasons: string[]
  warnings: string[]
  policyEvaluation: Record<string, unknown>
  preflightEvidence: Record<string, unknown>
  result: Record<string, unknown>
  verificationEvidence: Record<string, unknown>
  steps: Array<Record<string, unknown>>
  maintenanceApplied: boolean
  structuredResultUri: string | null
  structuredResultChecksum: string | null
  errorCode: string | null
  errorMessage: string | null
  plannedAt: string
  startedAt: string | null
  completedAt: string | null
}

export type Connection = {
  id: string
  environmentId: string
  name: string
  catalogType: string
  catalogUri: string | null
  warehouseUri: string | null
  engineType: string
  engineUri: string | null
  secretRef: string | null
  options?: Record<string, string>
  enabled: boolean
  lastTestedAt: string | null
  lastTestStatus: string | null
  lastTestMessage: string | null
}

export type DiscoveryRun = {
  id: string
  connectionId: string
  state: string
  rootUri: string | null
  tablesFound: number
  tablesMissing: number
  errorCode: string | null
  errorMessage: string | null
}

export type AuditEvent = {
  id: number
  occurredAt: string
  eventType: string
  severity: string
  principal: string | null
  resourceType: string | null
  resourceId: string | null
  details: Record<string, unknown>
}

export type ApiProblem = {
  type: string
  title: string
  status: number
  detail: string
  instance: string
  timestamp: string
  code: string
}
