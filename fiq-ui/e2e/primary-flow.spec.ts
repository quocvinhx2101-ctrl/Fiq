import { expect, test, type Page, type Route } from '@playwright/test'

const target = { type: 'PATH', uri: 's3a://lake/events' }
const table = { id: 'table-1', qualifiedName: 'main.analytics.events', executionTarget: target, sample: true, discoveryStatus: 'ACTIVE', environment: 'development', catalog: 'main', accessMode: 'CLASSIC', currentVersion: 42, fileCount: 128, totalBytes: 1073741824, debtScore: 63, severity: 'WARNING', healthCompleteness: 'COMPLETE', refreshedAt: '2026-08-24T10:00:00Z', features: ['deletionVectors'], readOnlyReason: null }
const operation = { id: 'op-1', workspaceId: '00000000-0000-0000-0000-000000000001', tableId: table.id, tableName: table.qualifiedName, executionTarget: target, policyId: 'policy-1', operationType: 'OPTIMIZE_BINPACK', state: 'PLANNED', basedOnVersion: 42, estimatedBytes: 1073741824, approvalRequired: false, approvedBy: null, externalJobId: null, commandPreview: 'OPTIMIZE delta.`s3a://lake/events`', reasons: [], warnings: [], policyEvaluation: { decision: 'ELIGIBLE' }, preflightEvidence: {}, result: {}, verificationEvidence: {}, steps: [], maintenanceApplied: false, structuredResultUri: null, structuredResultChecksum: null, errorCode: null, errorMessage: null, plannedAt: '2026-08-24T10:00:00Z', startedAt: null, completedAt: null }

async function mockApi(page: Page) {
  await page.route('**/api/v1/**', async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let value: unknown = {}
    if (path.endsWith('/bootstrap')) value = { workspace: { id: '00000000-0000-0000-0000-000000000001', name: 'Data platform', timezone: 'Asia/Ho_Chi_Minh' }, environment: { id: 'env-1', name: 'development', production: false }, role: 'ADMIN', timezone: 'Asia/Ho_Chi_Minh', serverTime: '2026-08-24T10:00:00Z', version: 'test' }
    else if (path.endsWith('/overview')) value = { tables: 1, warningTables: 1, criticalTables: 0, activeOperations: 0, failedOperations: 0, pendingApprovals: 0, reclaimableBytes: 0, refreshedAt: '2026-08-24T10:00:00Z' }
    else if (path.endsWith('/tables')) value = { items: [table], nextCursor: null }
    else if (path.endsWith(`/tables/${table.id}`)) value = { summary: table, location: 's3://lake/events', namespace: ['analytics'], partitionColumns: ['date'], clusteringColumns: [], properties: {}, tags: {}, capabilities: { OPTIMIZE_BINPACK: { supported: true, approvalRequired: false, reason: 'Supported' }, VACUUM_LITE: { supported: false, approvalRequired: false, reason: 'Log coverage is incomplete' } } }
    else if (path.endsWith('/health')) value = { id: 'health-1', tableId: table.id, observedVersion: 42, assessedAt: '2026-08-24T10:00:00Z', provenance: 'test', completeness: 'COMPLETE', staleReason: null, dimensions: { FILE_LAYOUT: { completeness: 'COMPLETE', provenance: 'Delta Kernel 4.0.1', observedAt: '2026-08-24T10:00:00Z', observedVersion: 42, incompleteReason: null }, RETENTION: { completeness: 'PARTIAL', provenance: 'Spark 4.0.1', observedAt: '2026-08-24T10:00:00Z', observedVersion: 42, incompleteReason: 'Tombstone bytes unavailable' } }, fileLayout: { activeFileCount: 128, totalBytes: 1073741824 }, deletionVectors: { dvFileCount: 4 }, transactionLog: {}, storageRetention: {}, clustering: {}, protocol: {}, debtScore: 63, issues: [] }
    else if (path.endsWith('/policies') && method === 'POST') value = { id: 'policy-new', name: 'Small-file policy', description: '', enabled: true, cron: '0 0 2 * * ?', timezone: 'UTC', operations: ['OPTIMIZE_BINPACK'], maxBytesPerRun: 0, maxConcurrentOperations: 1, updatedAt: '2026-08-24T10:00:00Z' }
    else if (path.endsWith('/policies')) value = { items: [{ id: 'policy-1', name: 'Daily compaction', description: 'Safe defaults', enabled: true, cron: '0 0 2 * * ?', timezone: 'UTC', operations: ['OPTIMIZE_BINPACK'], maxBytesPerRun: 2147483648, maxConcurrentOperations: 1, updatedAt: '2026-08-24T10:00:00Z' }], nextCursor: null }
    else if (path.endsWith('/operations/plan')) value = operation
    else if (path.endsWith('/operations')) value = { items: [operation], nextCursor: null }
    else if (path.endsWith('/connections') && method === 'POST') value = { id: 'connection-new', environmentId: 'env-1', name: 'Local Delta', catalogType: 'PATH', catalogUri: 's3a://fiq-samples', warehouseUri: null, engineType: 'LIVY', engineUri: 'http://livy:8998', secretRef: null, enabled: true, lastTestedAt: null, lastTestStatus: null, lastTestMessage: null }
    else if (path.endsWith('/connections')) value = [{ id: 'connection-1', environmentId: 'env-1', name: 'Sample PATH', catalogType: 'PATH', catalogUri: 's3a://fiq-samples', warehouseUri: null, engineType: 'LIVY', engineUri: 'http://livy:8998', secretRef: null, enabled: true, lastTestedAt: null, lastTestStatus: null, lastTestMessage: null }]
    else if (path.endsWith('/test')) value = { reachable: true, status: 'QUALIFIED', message: 'Spark 4.0.1 + Delta 4.0.1', capabilities: {} }
    else if (path.endsWith('/discover')) value = { id: 'run-1', connectionId: 'connection-1', state: 'SUCCEEDED', rootUri: 's3a://fiq-samples', tablesFound: 5, tablesMissing: 0, errorCode: null, errorMessage: null }
    else if (path.endsWith('/audit')) value = { items: [], nextCursor: null }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) })
  })
}

test.beforeEach(async ({ page }) => mockApi(page))

test('assess and review surfaces remain navigable', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Maintenance overview' })).toBeVisible()
  if (page.viewportSize()!.width < 1024) await page.getByRole('button', { name: 'Open navigation' }).click()
  await page.getByRole('link', { name: 'Tables', exact: true }).click()
  const tableLink = page.locator(`a[href="/tables/${table.id}"]:visible`).first()
  await expect(tableLink).toBeVisible()
  await tableLink.click()
  await expect(page.getByRole('heading', { name: table.qualifiedName })).toBeVisible()
  await page.getByRole('tab', { name: 'Policies' }).click()
  await expect(page.getByRole('heading', { name: 'Plan maintenance' })).toBeVisible()
})

test('dark mode keeps the primary queue readable', async ({ page }) => {
  await page.goto('/operations')
  await page.getByRole('button', { name: 'Switch to dark theme' }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  await expect(page.getByText('OPTIMIZE BINPACK')).toBeVisible()
})

test('core workflow controls are functional', async ({ page }) => {
  await page.goto('/connections')
  await page.getByRole('button', { name: 'Test capabilities' }).click()
  await expect(page.getByText('Spark 4.0.1 + Delta 4.0.1')).toBeVisible()
  await page.getByRole('button', { name: 'Discover' }).click()
  await expect(page.getByText(/5 table\(s\) found/)).toBeVisible()

  await page.goto('/policies')
  await page.getByRole('button', { name: 'New policy' }).click()
  await page.getByLabel('Name', { exact: true }).fill('Small-file policy')
  await page.getByRole('button', { name: 'Create policy' }).click()
  await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible()

  await page.goto(`/tables/${table.id}`)
  await page.getByRole('tab', { name: 'Policies' }).click()
  await page.getByRole('combobox', { name: 'Policy' }).selectOption('policy-1')
  await page.getByRole('button', { name: 'Create plan' }).click()
  await expect(page.getByText('Plan created')).toBeVisible()

  await page.goto('/operations')
  await page.getByText('View decision and before/after evidence').click()
  await expect(page.getByText('ASSESS / DECISION')).toBeVisible()
  await expect(page.getByText('PATH · s3a://lake/events')).toBeVisible()
})
